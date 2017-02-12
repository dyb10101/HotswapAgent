package org.hotswap.agent.plugin.owb.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.MergeableCommand;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.owb.BeanReloadStrategy;
import org.hotswap.agent.watch.WatchFileEvent;

/**
 * BeanClassRefreshCommand. Collect all classes definitions/redefinitions for application
 *
 * 1. Merge all commands (definition, redefinition) for appClassLoader to single command.
 * 2. Call proxy redefinitions in BeanArchiveAgent for all merged commands
 * 3. Call bean class reload in BeanArchiveAgent for all merged commands
 *
 * @author Vladimir Dvorak
 */
public class BeanClassRefreshCommand extends MergeableCommand {

    private static AgentLogger LOGGER = AgentLogger.getLogger(BeanClassRefreshCommand.class);

    ClassLoader appClassLoader;

    String archivePath;

    String className;

    String classSignForProxyCheck;

    String classSignByStrategy;

    String strBeanReloadStrategy;

    WatchFileEvent event;

    /**
     * Instantiates a new bean class refresh command.
     *
     * @param appClassLoader the application class loader
     * @param archivePath the archive path
     * @param className the class name
     * @param classSignForProxyCheck the class signature for proxy check
     * @param classSignByStrategy the class signature by strategy
     * @param beanReloadStrategy the bean reload strategy
     */
    public BeanClassRefreshCommand(ClassLoader appClassLoader, String archivePath, String className, String classSignForProxyCheck,
            String classSignByStrategy, BeanReloadStrategy beanReloadStrategy) {
        this.appClassLoader = appClassLoader;
        this.archivePath = archivePath;
        this.className = className;
        this.classSignForProxyCheck = classSignForProxyCheck;
        this.classSignByStrategy = classSignByStrategy;
        this.strBeanReloadStrategy = beanReloadStrategy != null ? beanReloadStrategy.toString() : null;
    }

    /**
     * Instantiates a new bean class refresh command.
     *
     * @param appClassLoader the application class loader
     * @param archivePath the archive path
     * @param event the class event
     */
    public BeanClassRefreshCommand(ClassLoader appClassLoader, String archivePath, WatchFileEvent event) {

        this.appClassLoader = appClassLoader;
        this.archivePath = archivePath;
        this.event = event;

        // strip from URI prefix up to basePackage and .class suffix.
        String classFullPath = event.getURI().getPath();
        int index = classFullPath.indexOf(archivePath);
        if (index == 0) {
            String classPath = classFullPath.substring(archivePath.length());
            classPath = classPath.substring(0, classPath.indexOf(".class"));
            if (classPath.startsWith("/")) {
                classPath = classPath.substring(1);
            }
            this.className = classPath.replace("/", ".");
        } else {
            LOGGER.error("Archive path '{}' doesn't match with classFullPath '{}'", archivePath, classFullPath);
        }
    }

    @Override
    public void executeCommand() {
        List<Command> mergedCommands = popMergedCommands();
        mergedCommands.add(0, this);

        do {
            // First step : recreate all proxies
            for (Command command: mergedCommands) {
                ((BeanClassRefreshCommand)command).recreateProxy(mergedCommands);
            }

            // Second step : reload beans
            for (Command command: mergedCommands) {
                ((BeanClassRefreshCommand)command).reloadBean(mergedCommands);
            }
            mergedCommands = popMergedCommands();
        } while (!mergedCommands.isEmpty());
    }

    private void recreateProxy(List<Command> mergedCommands) {
        if (isCreateEvent(mergedCommands) || isDeleteEvent(mergedCommands)) {
            LOGGER.trace("Skip OWB recreate proxy for delete event on class '{}'", className);
            return;
        }
        if (className != null) {
            try {
                LOGGER.debug("Executing BeanArchiveAgent.recreateProxy('{}')", className);
                Class<?> agentClazz = Class.forName(BeanArchiveAgent.class.getName(), true, appClassLoader);
                Method agentMethod  = agentClazz.getDeclaredMethod("recreateProxy",
                        new Class[] { ClassLoader.class,
                                      String.class,
                                      String.class,
                                      String.class
                        }
                );
                agentMethod.invoke(null,
                        appClassLoader,
                        archivePath,
                        className,
                        classSignForProxyCheck
                );
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Plugin error, method not found", e);
            } catch (InvocationTargetException e) {
                LOGGER.error("Error recreateProxy class {} in classLoader {}", e, className, appClassLoader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Plugin error, illegal access", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Plugin error, CDI class not found in classloader", e);
            }
        }
    }


    public void reloadBean(List<Command> mergedCommands) {
        if (isDeleteEvent(mergedCommands)) {
            LOGGER.trace("Skip OWB reload for delete event on class '{}'", className);
            return;
        }
        if (className != null) {
            try {
                LOGGER.debug("Executing BeanArchiveAgent.reloadBean('{}')", className);
                Class<?> agentClazz = Class.forName(BeanArchiveAgent.class.getName(), true, appClassLoader);
                Method agentMethod  = agentClazz.getDeclaredMethod("reloadBean",
                        new Class[] { ClassLoader.class,
                                      String.class,
                                      String.class,
                                      String.class,
                                      String.class
                        }
                );
                agentMethod.invoke(null,
                        appClassLoader,
                        archivePath,
                        className,
                        classSignByStrategy,
                        strBeanReloadStrategy // passed as String since BeanArchiveAgent has different classloader
                );
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Plugin error, method not found", e);
            } catch (InvocationTargetException e) {
                LOGGER.error("Error reloadBean class {} in classLoader {}", e, className, appClassLoader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Plugin error, illegal access", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Plugin error, CDI class not found in classloader", e);
            }
        }
    }

    /**
     * Check all merged events with same className for delete and create events. If delete without create is found, than assume
     * file was deleted.
     * @param mergedCommands
     */
    private boolean isDeleteEvent(List<Command> mergedCommands) {
        boolean createFound = false;
        boolean deleteFound = false;
        for (Command cmd : mergedCommands) {
            BeanClassRefreshCommand refreshCommand = (BeanClassRefreshCommand) cmd;
            if (className.equals(refreshCommand.className)) {
                if (refreshCommand.event != null) {
                    if (refreshCommand.event.getEventType().equals(FileEvent.DELETE))
                        deleteFound = true;
                    if (refreshCommand.event.getEventType().equals(FileEvent.CREATE))
                        createFound = true;
                }
            }
        }

        LOGGER.trace("isDeleteEvent result {}: createFound={}, deleteFound={}", createFound, deleteFound);
        return !createFound && deleteFound;
    }

    /**
     * Check all merged events with same className for create events.
     * @param mergedCommands
     */
    private boolean isCreateEvent(List<Command> mergedCommands) {
        boolean createFound = false;
        for (Command cmd : mergedCommands) {
            BeanClassRefreshCommand refreshCommand = (BeanClassRefreshCommand) cmd;
            if (className.equals(refreshCommand.className)) {
                if (refreshCommand.event != null) {
                    if (refreshCommand.event.getEventType().equals(FileEvent.CREATE))
                        createFound = true;
                }
            }
        }

        LOGGER.trace("isCreateEvent result {}: createFound={}", createFound);
        return !createFound;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BeanClassRefreshCommand that = (BeanClassRefreshCommand) o;

        if (!appClassLoader.equals(that.appClassLoader)) return false;
        if (archivePath != null && !archivePath.equals(that.archivePath)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = appClassLoader.hashCode();
        result = 31 * result + (className != null ? className.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "BeanClassRefreshCommand{" +
                "appClassLoader=" + appClassLoader +
                ", archivePath='" + archivePath + '\'' +
                ", className='" + className + '\'' +
                '}';
    }
}
