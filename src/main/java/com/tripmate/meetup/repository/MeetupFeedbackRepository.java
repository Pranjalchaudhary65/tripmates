package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.MeetupFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * The private comfort ratings. Package boundaries keep this repository's
 * readers to the meet-up module; MeetupModuleBoundaryTest fails the build if
 * any other module so much as names it.
 */
public interface MeetupFeedbackRepository extends JpaRepository<MeetupFeedback, UUID> {

    List<MeetupFeedback> findByMeetupId(UUID meetupId);

    boolean existsByMeetupIdAndUserId(UUID meetupId, UUID userId);
}
