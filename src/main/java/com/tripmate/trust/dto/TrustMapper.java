package com.tripmate.trust.dto;

import com.tripmate.trust.service.TrustScoreFormula;
import com.tripmate.trust.service.TrustScoreService.TrustScoreView;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TrustMapper {

    default TrustScoreResponse toResponse(TrustScoreView view) {
        return new TrustScoreResponse(view.score() == null, view.score(), view.computedAt(),
                view.breakdown() == null ? null : toResponse(view.breakdown()));
    }

    TrustScoreResponse.Breakdown toResponse(TrustScoreFormula.Breakdown breakdown);

    TrustScoreResponse.Verification toResponse(TrustScoreFormula.Verification verification);

    TrustScoreResponse.PeerRating toResponse(TrustScoreFormula.PeerRating peerRating);

    TrustScoreResponse.Completion toResponse(TrustScoreFormula.Completion completion);

    TrustScoreResponse.Reliability toResponse(TrustScoreFormula.Reliability reliability);

    TrustScoreResponse.Deduction toResponse(TrustScoreFormula.Deduction deduction);

    TrustScoreResponse.VouchPenalty toResponse(TrustScoreFormula.VouchPenalty vouchPenalty);
}
