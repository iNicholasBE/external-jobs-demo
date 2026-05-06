package org.jobrunr.demo.gemini;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/gemini-video")
public class GeminiVideoController {

    private final GeminiVideoService videoService;
    private final GeminiWebhookSetup setup;
    private final GeminiConfig config;

    public GeminiVideoController(GeminiVideoService videoService, GeminiWebhookSetup setup, GeminiConfig config) {
        this.videoService = videoService;
        this.setup = setup;
        this.config = config;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("activeJobs", videoService.getActiveJobs());
        model.addAttribute("completedJobs", videoService.getCompletedJobs());
        model.addAttribute("configured", config.isConfigured());
        model.addAttribute("ready", setup.isReady());
        model.addAttribute("webhookId", setup.webhookId());
        model.addAttribute("webhookUrl", config.isConfigured() ? config.webhookUrl() : null);
        model.addAttribute("videoModel", config.videoModel());
        model.addAttribute("setupError", setup.setupError());
        return "gemini-video";
    }

    @PostMapping("/launch")
    public String launch(@RequestParam String prompt, RedirectAttributes attrs) {
        if (!setup.isReady()) {
            attrs.addFlashAttribute("error", "Gemini webhook is not registered. Set GEMINI_API_KEY and GEMINI_PUBLIC_URL, then restart.");
            return "redirect:/gemini-video";
        }
        videoService.launch(prompt);
        attrs.addFlashAttribute("success", "Submitted to Veo — waiting for the video.generated webhook…");
        return "redirect:/gemini-video";
    }

    /** Serves the cached MP4 bytes back to the browser <video> tag. */
    @GetMapping("/file/{jobId}.mp4")
    public ResponseEntity<byte[]> serveVideo(@PathVariable UUID jobId) {
        byte[] bytes = videoService.getVideoBytes(jobId);
        if (bytes == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(bytes);
    }
}
