package com.dbx.connection.api;

/**
 * Why no entry point can do its work: the master key is {@link MasterKeyUnavailable} or
 * {@link MasterKeyMalformed} (obligations 8, 9).
 *
 * <p>Thrown rather than returned, because it is not an outcome of the value being handled. Until the
 * DBA restores {@code secrets/master.key}, every entry point fails the same way, so returning it would
 * put the same branch in every caller and invite one that forgets. The class is sealed, so a caller
 * that does distinguish the two — the installation and environment checks do — can switch
 * exhaustively.
 *
 * <p>It never carries key bytes and never quotes file content (obligation 11); naming the path it
 * looked at is what a DBA needs and reveals nothing secret.
 */
public abstract sealed class MasterKeyFailure extends RuntimeException
        permits MasterKeyUnavailable, MasterKeyMalformed {

    MasterKeyFailure(String message) {
        super(message);
    }
}
