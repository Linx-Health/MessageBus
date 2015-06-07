package dorkbox.util.messagebus.utils;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.subscription.Subscription;

import java.util.ArrayList;
import java.util.Map;

public final class VarArgUtils {
    private final Map<Class<?>, ArrayList<Subscription>> varArgSubscriptionsSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> varArgSubscriptionsMulti;

    private final Map<Class<?>, ArrayList<Subscription>> varArgSuperSubscriptionsSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> varArgSuperSubscriptionsMulti;

    private final ClassUtils superClassUtils;
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle;


    public VarArgUtils(final ClassUtils superClassUtils, final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle,
                       final float loadFactor, final int stripeSize) {

        this.superClassUtils = superClassUtils;
        this.subscriptionsPerMessageSingle = subscriptionsPerMessageSingle;

        this.varArgSubscriptionsSingle = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, loadFactor);

        this.varArgSuperSubscriptionsSingle = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSuperSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, loadFactor);
    }


    public void clear() {
        this.varArgSubscriptionsSingle.clear();
        this.varArgSubscriptionsMulti.clear();

        this.varArgSuperSubscriptionsSingle.clear();
        this.varArgSuperSubscriptionsMulti.clear();
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Subscription[] getVarArgSubscriptions(final Class<?> messageClass) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, ArrayList<Subscription>> local = this.varArgSubscriptionsSingle;

        ArrayList<Subscription> varArgSubs = local.get(messageClass);

        if (varArgSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);

            ArrayList<Subscription> subs = this.subscriptionsPerMessageSingle.get(arrayVersion);
            if (subs != null) {
                final int length = subs.size();
                varArgSubs = new ArrayList<Subscription>(length);

                Subscription sub;
                for (int i = 0; i < length; i++) {
                    sub = subs.get(i);

                    if (sub.getHandler().acceptsVarArgs()) {
                        varArgSubs.add(sub);
                    }
                }

                local.put(messageClass, varArgSubs);
            }
        }

        final Subscription[] subscriptions = new Subscription[varArgSubs.size()];
        varArgSubs.toArray(subscriptions);
        return subscriptions;
    }



    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass) {
        final ArrayList<Subscription> subs = getVarArgSuperSubscriptions_List(messageClass);

        final Subscription[] subscriptions = new Subscription[subs.size()];
        subs.toArray(subscriptions);
        return subscriptions;
    }

    // CAN NOT RETURN NULL
    private ArrayList<Subscription> getVarArgSuperSubscriptions_List(final Class<?> messageClass) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, ArrayList<Subscription>> local = this.varArgSuperSubscriptionsSingle;

        ArrayList<Subscription> varArgSuperSubs = local.get(messageClass);

        if (varArgSuperSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);
            final Class<?>[] types = this.superClassUtils.getSuperClasses(arrayVersion);

            final int typesLength = types.length;
            varArgSuperSubs = new ArrayList<Subscription>(typesLength);

            if (typesLength == 0) {
                local.put(messageClass, varArgSuperSubs);
                return varArgSuperSubs;
            }


            Class<?> type;
            Subscription sub;
            ArrayList<Subscription> subs;
            int length;
            MessageHandler handlerMetadata;

            for (int i = 0; i < typesLength; i++) {
                type = types[i];
                subs = this.subscriptionsPerMessageSingle.get(type);

                if (subs != null) {
                    length = subs.size();
                    varArgSuperSubs = new ArrayList<Subscription>(length);

                    for (int j = 0; j < length; j++) {
                        sub = subs.get(j);

                        handlerMetadata = sub.getHandler();
                        if (handlerMetadata.acceptsSubtypes() && handlerMetadata.acceptsVarArgs()) {
                            varArgSuperSubs.add(sub);
                        }
                    }

                }
            }

            local.put(messageClass, varArgSuperSubs);
        }

        return varArgSuperSubs;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperSubscriptionsMulti;

        ArrayList<Subscription> subs = local.get(messageClass1, messageClass2);

        if (subs == null) {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1);
            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2);

            subs = ClassUtils.findCommon(varargSuperSubscriptions1, varargSuperSubscriptions2);

            subs.trimToSize();
            local.put(subs, messageClass1, messageClass2);
        }

        final Subscription[] subscriptions = new Subscription[subs.size()];
        subs.toArray(subscriptions);
        return subscriptions;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2,
                                                      final Class<?> messageClass3) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperSubscriptionsMulti;

        ArrayList<Subscription> subs = local.get(messageClass1, messageClass2, messageClass3);

        if (subs == null) {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1);
            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2);
            final ArrayList<Subscription> varargSuperSubscriptions3 = getVarArgSuperSubscriptions_List(messageClass3);

            subs = ClassUtils.findCommon(varargSuperSubscriptions1, varargSuperSubscriptions2);
            subs = ClassUtils.findCommon(subs, varargSuperSubscriptions3);

            subs.trimToSize();
            local.put(subs, messageClass1, messageClass2, messageClass3);
        }

        final Subscription[] subscriptions = new Subscription[subs.size()];
        subs.toArray(subscriptions);
        return subscriptions;
    }


}
