package com.tripmate.moderation.dto;

import com.tripmate.identity.service.UserBlocks.BlockView;
import com.tripmate.moderation.dto.ModerationDtos.BlockListResponse;
import com.tripmate.moderation.dto.ModerationDtos.BlockResponse;
import com.tripmate.moderation.dto.ModerationDtos.ReportResponse;
import com.tripmate.moderation.service.ModerationService.ReportView;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ModerationMapper {

    BlockResponse toResponse(BlockView block);

    List<BlockResponse> toResponses(List<BlockView> blocks);

    default BlockListResponse toListResponse(List<BlockView> blocks) {
        return new BlockListResponse(toResponses(blocks));
    }

    ReportResponse toResponse(ReportView report);
}
