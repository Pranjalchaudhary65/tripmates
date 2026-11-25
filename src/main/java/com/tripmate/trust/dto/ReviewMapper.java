package com.tripmate.trust.dto;

import com.tripmate.trust.repository.ReviewQueries.ReceivedReview;
import com.tripmate.trust.service.ReviewService;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReviewMapper {

    TripReviewsResponse toResponse(ReviewService.TripReviews reviews);

    TripReviewsResponse.Trip toResponse(ReviewService.TripRef trip);

    TripReviewsResponse.Window toResponse(ReviewService.Window window);

    TripReviewsResponse.Person toResponse(ReviewService.Person person);

    TripReviewsResponse.WrittenReview toResponse(ReviewService.WrittenReview review);

    MyReviewsResponse toResponse(ReviewService.MyReviews reviews);

    MyReviewsResponse.TripToReview toResponse(ReviewService.TripToReview trip);

    MyReviewsResponse.Received toResponse(ReceivedReview review);
}
