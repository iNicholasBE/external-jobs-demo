package org.jobrunr.demo.gpu;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/gpu")
public class GpuJobController {

    private final GpuJobService gpuJobService;

    public GpuJobController(GpuJobService gpuJobService) {
        this.gpuJobService = gpuJobService;
    }

    @GetMapping
    public String gpuPage(Model model) {
        model.addAttribute("activeJobs", gpuJobService.getActiveJobs());
        model.addAttribute("completedJobs", gpuJobService.getCompletedJobs());
        return "gpu";
    }

    @PostMapping("/launch")
    public String launch(@RequestParam String prompt, RedirectAttributes attrs) {
        gpuJobService.launch(prompt);
        attrs.addFlashAttribute("success", "GPU job launched — video is being generated on Replicate...");
        return "redirect:/gpu";
    }
}
