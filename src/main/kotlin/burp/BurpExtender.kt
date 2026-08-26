package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import net.portswigger.mcp.ExtensionBase

/**
 * Conventional Burp Java entrypoint.
 *
 * Some Burp loaders do not discover a Montoya implementation when the main
 * class uses an arbitrary package/name. Keep the upstream implementation in
 * ExtensionBase and delegate through the standard burp.BurpExtender name.
 */
@Suppress("unused")
class BurpExtender : BurpExtension {
    private val delegate = ExtensionBase()

    override fun initialize(api: MontoyaApi) {
        delegate.initialize(api)
    }
}
