package com.moneyflow.goal;

import com.moneyflow.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {
    private final GoalService goalService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<GoalResponse>>> getGoals(
            @RequestParam(required = false) String status) {
        String userId = getCurrentUserId();
        List<GoalResponse> goals = status != null
                ? goalService.getGoalsByStatus(userId, status)
                : goalService.getGoals(userId);
        return ResponseEntity.ok(ApiResponse.success(goals));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GoalResponse>> getGoal(@PathVariable String id) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(goalService.getGoal(userId, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GoalResponse>> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        String userId = getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        goalService.createGoal(userId, request), "Goal created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<GoalResponse>> updateGoal(
            @PathVariable String id, @Valid @RequestBody UpdateGoalRequest request) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(goalService.updateGoal(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteGoal(@PathVariable String id) {
        String userId = getCurrentUserId();
        goalService.deleteGoal(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null, "Goal deleted successfully"));
    }

    @PutMapping("/reorder")
    public ResponseEntity<ApiResponse<Void>> reorderGoals(@Valid @RequestBody ReorderGoalRequest request) {
        String userId = getCurrentUserId();
        goalService.reorderGoals(userId, request);
        return ResponseEntity.noContent().build();
    }

    private String getCurrentUserId() {
        // TODO: replace with real SecurityContext extraction once prod profile
        // JWT filter is active in this dev session
        return "hardcoded-dev-user-id";
    }
}
