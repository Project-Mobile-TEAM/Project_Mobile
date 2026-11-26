package com.utechat.data.repository;

import java.util.List;

/** Bộ 15 người của bước nhận diện bạn bè (F-04): chọn đúng 3 bạn. expiresAtMillis theo System.currentTimeMillis. */
public final class FriendChallenge {
    public static final int PICK = 3;

    public static final class Candidate {
        public final String id;
        public final String displayName;

        public Candidate(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    public final String challengeId;
    public final List<Candidate> candidates;
    public final int attemptsLeft;
    public final boolean lastAttemptWrong;
    public final long expiresAtMillis;

    public FriendChallenge(String challengeId, List<Candidate> candidates, int attemptsLeft, boolean lastAttemptWrong, long expiresAtMillis) {
        this.challengeId = challengeId;
        this.candidates = candidates;
        this.attemptsLeft = attemptsLeft;
        this.lastAttemptWrong = lastAttemptWrong;
        this.expiresAtMillis = expiresAtMillis;
    }
}
