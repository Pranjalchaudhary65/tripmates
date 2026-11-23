package com.tripmate.vibe.dto;

import com.tripmate.vibe.entity.DealBreakers;
import com.tripmate.vibe.entity.VibeProfile;
import com.tripmate.vibe.service.VibeQuiz;
import com.tripmate.vibe.service.VibeService;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Entities and service results to responses, field by field. An option's
 * scoring value is never mapped: the client needs the label, and seeing the
 * numbers would only invite answering for a result.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VibeMapper {

    VibeProfileResponse.Profile toResponse(VibeProfile profile);

    VibeProfileResponse.DealBreakersView toResponse(DealBreakers dealBreakers);

    QuizResponse.Question toResponse(VibeQuiz.Question question);

    QuizResponse.Option toResponse(VibeQuiz.Option option);

    QuizResponse.Draft toResponse(VibeService.Draft draft);

    default VibeProfileResponse toResponse(VibeService.ProfileView view) {
        return new VibeProfileResponse(view.profile() == null ? null : toResponse(view.profile()),
                view.dealBreakers() == null ? null : toResponse(view.dealBreakers()));
    }

    default QuizResponse toResponse(VibeService.QuizView view) {
        List<QuizResponse.Question> questions = VibeQuiz.questions().stream().map(this::toResponse).toList();
        return new QuizResponse(view.version(), questions, view.draft() == null ? null : toResponse(view.draft()),
                view.hasProfile());
    }
}
