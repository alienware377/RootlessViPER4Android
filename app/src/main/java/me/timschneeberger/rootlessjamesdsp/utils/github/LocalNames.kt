package me.timschneeberger.rootlessjamesdsp.utils.github

/**
 * Decides the local file name for each remote file.
 *
 * The parked version de-duplicated by file name, which silently threw real
 * files away: AutoEq has 2240 of its 10114 files under a name another
 * folder also uses. Now every file is offered, and a clash gets the folder
 * it came from added to its name.
 */
object LocalNames {

    /** [localName] null means the library already has this file. */
    data class Plan(val file: RemoteFile, val localName: String?) {
        val alreadyHave get() = localName == null
        val renamed get() = localName != null && localName != LocalNames.sanitize(file.name)
    }

    /**
     * @param existing lower-cased names of the files in the library, with their sizes
     * @param repoName used as the tag for files at the top of the repository
     */
    fun plan(files: List<RemoteFile>, existing: Map<String, Long>, repoName: String): List<Plan> {
        val taken = HashSet(existing.keys)
        val claimed = HashSet<String>()
        return files.map { planOne(it, existing, taken, claimed, repoName) }
    }

    private fun planOne(
        f: RemoteFile, existing: Map<String, Long>, taken: MutableSet<String>,
        claimed: MutableSet<String>, repoName: String,
    ): Plan {
        val base = sanitize(f.name)
        val tag = sanitize(f.folder.substringAfterLast('/').ifEmpty { repoName.substringAfterLast('/') })
        var n = 0
        while (true) {
            val candidate = if (n == 0) base else withTag(base, if (n == 1) tag else "$tag $n")
            val key = candidate.lowercase()
            val have = existing[key]
            // A file of the same name and size is the same file. A Git LFS
            // pointer's size says nothing about the real file, so the name has to do.
            // One library file stands for one remote file only: two folders can
            // hold different files of the same name and size, and on a second
            // visit the second one used to match the first one's download and
            // was shown as already downloaded when it never was.
            if (have != null && (have == f.size || f.maybeLfs) && claimed.add(key)) return Plan(f, null)
            if (key !in taken) {
                taken += key
                return Plan(f, candidate)
            }
            n++
        }
    }

    /** "name.ext" with a tag: "name (tag).ext". */
    fun withTag(name: String, tag: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($tag)" else "${name.substring(0, dot)} ($tag)${name.substring(dot)}"
    }

    /** Characters shared storage refuses in a file name become "_". */
    fun sanitize(name: String): String =
        name.map { c -> if (c.code < 32 || c == 127.toChar() || c in "\"*/:<>?\\|") '_' else c }
            .joinToString("").trim().ifEmpty { "_" }
}
