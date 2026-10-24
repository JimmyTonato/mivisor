// Copiado sin cambios de https://github.com/acestream/acestream-engine-client (MIT, (c) 2019 Ace Stream).
package org.acestream.engine.service.v0;

/**
 * Callback to receive result of "startEngineWithCallback" command.
 */
oneway interface IStartEngineResponse {
    void onResult(boolean success);
}