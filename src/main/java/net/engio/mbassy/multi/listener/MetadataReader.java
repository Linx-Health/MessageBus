package net.engio.mbassy.multi.listener;

import java.lang.reflect.Method;
import java.util.Collection;

import net.engio.mbassy.multi.annotations.Handler;
import net.engio.mbassy.multi.common.ReflectionUtils;
import net.engio.mbassy.multi.common.StrongConcurrentSet;

/**
 * The meta data reader is responsible for parsing and validating message handler configurations.
 *
 * @author bennidi
 *         Date: 11/16/12
 */
public class MetadataReader {

    // get all listeners defined by the given class (includes
    // listeners defined in super classes)
    public MessageListener getMessageListener(Class<?> target) {

        // get all handlers (this will include all (inherited) methods directly annotated using @Handler)
        Collection<Method> allHandlers = ReflectionUtils.getMethods(target);

        // retain only those that are at the bottom of their respective class hierarchy (deepest overriding method)
        Collection<Method> bottomMostHandlers = new StrongConcurrentSet<Method>(allHandlers.size(), .8F);
        for (Method handler : allHandlers) {
            if (!ReflectionUtils.containsOverridingMethod(allHandlers, handler)) {
                bottomMostHandlers.add(handler);
            }
        }

        MessageListener listenerMetadata = new MessageListener(target, bottomMostHandlers.size());

        // for each handler there will be no overriding method that specifies @Handler annotation
        // but an overriding method does inherit the listener configuration of the overwritten method
        for (Method handler : bottomMostHandlers) {
            Handler handlerConfig = ReflectionUtils.getAnnotation(handler, Handler.class);
            if (handlerConfig == null || !handlerConfig.enabled()) {
                continue; // disabled or invalid listeners are ignored
            }

            Method overriddenHandler = ReflectionUtils.getOverridingMethod(handler, target);
            if (overriddenHandler == null) {
                overriddenHandler = handler;
            }

            // if a handler is overwritten it inherits the configuration of its parent method
            MessageHandler handlerMetadata = new MessageHandler(overriddenHandler, handlerConfig, listenerMetadata);
            listenerMetadata.addHandler(handlerMetadata);
        }
        return listenerMetadata;
    }
}