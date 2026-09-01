package com.moneyflow.invite;

import com.moneyflow.shared.dto.ApiResponse;
import com.moneyflow.shared.security.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/invites")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminInviteController extends BaseController {
    private final InviteService inviteService;

    @PostMapping
    public ResponseEntity<ApiResponse<InviteResponse>> createInvite(@Valid @RequestBody CreateInviteRequest request) {
        InviteResult result = inviteService.createInvite(getCurrentUserId(), request);

        return result.warning() != null
                ? ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.successWithWarning(result.response(), "Invite created", result.warning()))
                : ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result.response(), "Invite created"));
    }
}
