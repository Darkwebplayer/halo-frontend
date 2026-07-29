package dev.infyplus.halo

/**
 * Single-user MVP config. The token is a shared secret baked into the client — fine while
 * Sam is the only user, but it must move to a real per-device credential before this ships
 * to anyone else.
 */
object Config {
    /**
     * Still `jarvis` after the rename to Halo, deliberately: this is the deployed Cloudflare
     * Worker's hostname, not a name we control from here. Changing this string without renaming
     * and redeploying the worker in `AppBackend` first points the app at nothing.
     */
    const val BASE_URL = "https://jarvis.darkwebplayer101.workers.dev"
    const val AUTH_TOKEN = "01a506b183b09026b3282f4267e4d9bc2862b70bb65b3fda"
}
