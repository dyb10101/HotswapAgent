package org.hotswap.agent.plugin.proxy.hscglib;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hotswap.agent.javassist.ClassPool;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.proxy.ProxyTransformationUtils;

/**
 * @author Erki Ehtla
 * 
 */
public class GeneratorParametersRecorder {
	// this Map is used in the App ClassLoader
	public static Map<String, GeneratorParams> generatorParams = new ConcurrentHashMap<>();
	private static AgentLogger LOGGER = AgentLogger.getLogger(GeneratorParametersRecorder.class);
	
	public static void register(Object generatorStrategy, Object classGenerator, byte[] bytes) {
		try {
			ClassPool classPool = ProxyTransformationUtils.getClassPool(GeneratorParametersRecorder.class
					.getClassLoader());
			CtClass cc = classPool.makeClass(new ByteArrayInputStream(bytes), false);
			generatorParams.put(cc.getName(), new GeneratorParams(generatorStrategy, classGenerator));
			cc.detach();
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Error saving parameters of a creation of a Cglib proxy", e);
		}
	}
}
