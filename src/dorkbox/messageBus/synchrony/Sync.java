/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus.synchrony;

import dorkbox.messageBus.dispatch.Dispatch;


/**
 * @author dorkbox, llc Date: 2/2/15
 */
@SuppressWarnings("Duplicates")
public final
class Sync implements Synchrony {

    private final Dispatch dispatch;

    public
    Sync(final Dispatch dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public
    void publish(final Object message1) {
        dispatch.publish(message1);
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        dispatch.publish(message1, message2);
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        dispatch.publish(message1, message2, message3);
    }

    @Override
    public
    void shutdown() {
    }

    @Override
    public
    boolean hasPendingMessages() {
        return false;
    }
}
