package com.tripmate.identity.dto;

import com.tripmate.identity.service.AdminVerificationService;
import com.tripmate.identity.service.PhoneVerificationService;
import com.tripmate.identity.service.VerificationOverviewService;
import com.tripmate.identity.service.VouchService;
import com.tripmate.identity.upload.SignedMediaUrls;
import com.tripmate.identity.verification.SelfieChallenges;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.time.Duration;

/**
 * Service results to responses, field by field. An unmapped response field
 * fails the build, so nothing a service holds goes out unless a response
 * names it.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VerificationMapper {

    VerificationOverviewResponse toResponse(VerificationOverviewService.Overview overview);

    VerificationOverviewResponse.Phone toResponse(VerificationOverviewService.PhoneState phone);

    VerificationOverviewResponse.Selfie toResponse(VerificationOverviewService.SelfieState selfie);

    VerificationOverviewResponse.Vouches toResponse(VerificationOverviewService.VouchCounts vouches);

    MediaLinkResponse toResponse(SignedMediaUrls.SignedMediaUrl link);

    PhoneCodeSentResponse toResponse(PhoneVerificationService.PhoneCodeSent sent);

    SelfieChallengeResponse toResponse(SelfieChallenges.Challenge challenge);

    VouchSummaryResponse toResponse(VouchService.VouchSummary summary);

    VouchSummaryResponse.Given toResponse(VouchService.GivenVouch vouch);

    ReviewItemResponse toResponse(AdminVerificationService.ReviewItem item);

    /** Durations go out as whole seconds, like expiresIn everywhere else in the API. */
    default long seconds(Duration duration) {
        return duration.toSeconds();
    }
}
