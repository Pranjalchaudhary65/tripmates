package com.tripmate.trips.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.repository.TripQueries;
import com.tripmate.trips.service.ConflictPremortem.Topic;
import com.tripmate.trips.service.TripCrew.Crew;
import com.tripmate.vibe.service.MatchProfiles;
import com.tripmate.vibe.service.MatchProfiles.MatchProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The conflict pre-mortem for a locked trip: for the caller and each other
 * traveller, the few gaps between their vibes worth talking through before
 * leaving. Only pairs that include the caller, so nobody reads how two other
 * people differ; only fixed sentences, never a profile value.
 */
@Service
public class PremortemService {

    static final String READ_BUCKET = "trip-read";

    private final AccountService accounts;

    private final TripCrew crews;

    private final MatchProfiles profiles;

    private final ConflictPremortem premortem;

    private final TripQueries queries;

    private final RateLimiter rateLimiter;

    private final TripProperties properties;

    public PremortemService(AccountService accounts, TripCrew crews, MatchProfiles profiles,
                            ConflictPremortem premortem, TripQueries queries, RateLimiter rateLimiter,
                            TripProperties properties) {
        this.accounts = accounts;
        this.crews = crews;
        this.profiles = profiles;
        this.premortem = premortem;
        this.queries = queries;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Premortem forTrip(SessionPrincipal caller, UUID tripId) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
        accounts.requireActive(caller);
        UUID me = caller.userId();
        Crew crew = crews.of(tripId).filter(found -> found.isTraveller(me))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        Map<UUID, MatchProfile> vibes = profiles.of(crew.travellers());
        Map<UUID, String> names = queries.firstNames(crew.travellers());
        MatchProfile mine = vibes.get(me);
        List<Pair> pairs = new ArrayList<>();
        for (UUID other : crew.travellers()) {
            if (other.equals(me)) {
                continue;
            }
            String name = names.getOrDefault(other, "Your companion");
            MatchProfile theirs = vibes.get(other);
            pairs.add(new Pair(name, theirs != null,
                    mine == null || theirs == null ? List.of()
                            : premortem.topics(mine.dimensions(), theirs.dimensions(), name)));
        }
        return new Premortem(tripId, crew.startDate(), crew.isLocked(), mine != null, pairs);
    }

    /**
     * @param myVibeSet false until the caller takes the vibe quiz; every pair is then empty
     */
    public record Premortem(UUID tripId, LocalDate startDate, boolean locked, boolean myVibeSet, List<Pair> pairs) {
    }

    /** @param vibeSet false when the other traveller has not taken the quiz, so nothing can be compared */
    public record Pair(String firstName, boolean vibeSet, List<Topic> topics) {
    }
}
