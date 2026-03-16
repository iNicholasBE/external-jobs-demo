package org.jobrunr.demo.approval;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final AiApprovalService approvalService;

    public ApprovalController(AiApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public String approvalsPage(Model model) {
        model.addAttribute("analyzingReviews", approvalService.getAnalyzingReviews());
        model.addAttribute("pendingReviews", approvalService.getPendingReviews());
        model.addAttribute("completedReviews", approvalService.getCompletedReviews());
        return "approvals";
    }

    @PostMapping("/generate")
    public String generate(@RequestParam(required = false) String productName, RedirectAttributes attrs) {
        if (productName == null || productName.isBlank()) {
            productName = approvalService.randomProductName();
        }
        approvalService.createReviewRequest(productName);
        attrs.addFlashAttribute("success", "AI content generation started for: " + productName);
        return "redirect:/approvals";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id, RedirectAttributes attrs) {
        approvalService.approve(id);
        attrs.addFlashAttribute("success", "Content approved and published!");
        return "redirect:/approvals";
    }

    @PostMapping("/{id}/decline")
    public String decline(@PathVariable UUID id, RedirectAttributes attrs) {
        approvalService.decline(id);
        attrs.addFlashAttribute("error", "Content declined.");
        return "redirect:/approvals";
    }
}
