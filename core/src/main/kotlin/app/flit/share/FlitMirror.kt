package app.flit.share

/**
 * Album mirror (docs, milestone D): trusted-peer forward-sync of new camera
 * files — deduped by a ledger, checkpointed by the send-later queue, flushed
 * by the queue's own touch paths. The Android wiring (the `DCIM/Camera`
 * watcher and the scan) lives in [FlitEngine]; everything that must hold
 * without a device lives here.
 */
object FlitMirror {

    /** One file the scan or the watcher saw, reduced to what matters. */
    data class Shot(
        val name: String,
        val size: Long,
        /** The queue token that will carry it: a MediaStore uri (shared storage
         *  is never readable by raw path — scoped storage says so, and the
         *  first walk proved it with an EACCES). */
        val token: String,
    ) {
        /** The ledger identity: a camera name is timestamped; size separates an edit. */
        val ledgerToken: String get() = FlitMirror.ledgerToken(name, size)
    }

    fun ledgerToken(name: String, size: Long): String = "$name\u0000$size"

    /**
     * The pure diff the scan runs: files the ledger has never marked. A
     * zero-length file is a write still in progress — never a candidate; the
     * watcher's CLOSE_WRITE and the next scan will meet it whole.
     */
    fun plan(folder: List<Shot>, ledger: Collection<String>): List<Shot> {
        val seen = ledger.toSet()
        return folder.filter { it.size > 0L && it.ledgerToken !in seen }
    }

    /**
     * A finished file the watcher just saw. Returns null when it is not a
     * candidate: empty (still writing), or already mirrored.
     */
    fun notice(name: String, size: Long, ledger: Collection<String>): Boolean {
        if (size <= 0L) return false
        return ledgerToken(name, size) !in ledger.toSet()
    }
}
