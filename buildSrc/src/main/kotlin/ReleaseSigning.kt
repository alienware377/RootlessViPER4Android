import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The private key for release builds of the app and the voice isolation add-on, when one is set
 * up: in keystore/release.properties (never committed) with storeFile, storePassword, keyAlias and
 * keyPassword, or in the RV4A_RELEASE_STORE_FILE, RV4A_RELEASE_STORE_PASSWORD,
 * RV4A_RELEASE_KEY_ALIAS and RV4A_RELEASE_KEY_PASSWORD environment variables. A relative
 * storeFile is taken from the project root.
 *
 * Both modules read the same key, so a release app and a release add-on always match. That
 * matters because the app runs the add-on's code in its own process once the signatures agree,
 * and keystore/debug.keystore is in this repository: while releases are signed with it, anyone
 * can build an add-on the app accepts. Without a private key, releases keep the debug key, since
 * a phone only updates an app signed with the key it was installed with; moving to a new one
 * means uninstalling first, so it is a choice made once, not a default.
 */
class ReleaseKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun Project.releaseKey(): ReleaseKey? {
    val props = Properties()
    val file = rootProject.file("keystore/release.properties")
    if (file.isFile) file.inputStream().use(props::load)

    fun value(name: String, env: String): String? =
        props.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val values = linkedMapOf(
        "storeFile" to value("storeFile", "RV4A_RELEASE_STORE_FILE"),
        "storePassword" to value("storePassword", "RV4A_RELEASE_STORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "RV4A_RELEASE_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "RV4A_RELEASE_KEY_PASSWORD"),
    )
    if (values.values.all { it == null }) return null
    // Half a setup is a mistake, not a reason to fall back to the public key without a word.
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) throw GradleException("Release signing is missing ${missing.joinToString()}")

    val store = rootProject.file(values.getValue("storeFile")!!)
    if (!store.isFile) throw GradleException("Release keystore $store does not exist")
    return ReleaseKey(store, values.getValue("storePassword")!!, values.getValue("keyAlias")!!,
        values.getValue("keyPassword")!!)
}
