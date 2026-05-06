package org.jobrunr.demo.gemini;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/gemini")
public class GeminiPageController {

    private final GeminiBatchService batchService;
    private final GeminiWebhookSetup setup;
    private final GeminiConfig config;

    public GeminiPageController(GeminiBatchService batchService, GeminiWebhookSetup setup, GeminiConfig config) {
        this.batchService = batchService;
        this.setup = setup;
        this.config = config;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("activeJobs", batchService.getActiveJobs());
        model.addAttribute("completedJobs", batchService.getCompletedJobs());
        model.addAttribute("configured", config.isConfigured());
        model.addAttribute("ready", setup.isReady());
        model.addAttribute("webhookId", setup.webhookId());
        model.addAttribute("webhookUrl", config.isConfigured() ? config.webhookUrl() : null);
        model.addAttribute("model", config.model());
        model.addAttribute("setupError", setup.setupError());
        return "gemini";
    }

    @PostMapping("/launch")
    public String launch(@RequestParam String prompt, RedirectAttributes attrs) {
        if (!setup.isReady()) {
            attrs.addFlashAttribute("error", "Gemini webhook is not registered. Set GEMINI_API_KEY and GEMINI_PUBLIC_URL, then restart.");
            return "redirect:/gemini";
        }
        batchService.launch(prompt);
        attrs.addFlashAttribute("success", "Submitted to Gemini — waiting for the webhook to fire…");
        return "redirect:/gemini";
    }
}
