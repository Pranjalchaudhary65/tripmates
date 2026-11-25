package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.UserBlock;
import com.tripmate.identity.repository.UserBlockRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Blocks between accounts. Who may block whom (only someone the caller has
 * actually been matched or travelled with) is decided by the moderation
 * module before it calls here; this class only records, lists and lifts.
 * Every method runs in the caller's transaction, after the caller's account
 * has been checked and locked.
 */
@Service
public class UserBlocks {

    private final UserBlockRepository blocks;

    private final UserRepository users;

    public UserBlocks(UserBlockRepository blocks, UserRepository users) {
        this.blocks = blocks;
        this.users = users;
    }

    /** Blocking someone already blocked returns the existing block. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BlockView block(UUID blockerId, UUID blockedId, Instant now) {
        UserBlock block = blocks.findByBlockerIdAndBlockedId(blockerId, blockedId)
                .orElseGet(() -> blocks.save(UserBlock.of(blockerId, blockedId, now)));
        return new BlockView(block.getId(), firstName(users.findById(blockedId).orElse(null)), block.getCreatedAt());
    }

    /** Only the blocker can lift a block; for anyone else it does not exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unblock(UUID blockerId, UUID blockId) {
        UserBlock block = blocks.findById(blockId)
                .filter(found -> found.getBlockerId().equals(blockerId))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        blocks.delete(block);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<BlockView> blockedBy(UUID blockerId) {
        List<UserBlock> mine = blocks.findTop200ByBlockerIdOrderByCreatedAtDesc(blockerId);
        Map<UUID, User> blocked = users.findAllById(mine.stream().map(UserBlock::getBlockedId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return mine.stream()
                .map(block -> new BlockView(block.getId(), firstName(blocked.get(block.getBlockedId())),
                        block.getCreatedAt()))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean blockedBetween(UUID one, UUID other) {
        return blocks.existsBetween(one, other);
    }

    private static String firstName(User user) {
        return user == null ? "" : TrustScoreAccounts.firstNameOf(user.getFullName());
    }

    /** The blocked person is shown by first name only: the blocker knows who they blocked. */
    public record BlockView(UUID id, String firstName, Instant since) {
    }
}
