package com.localexperts.controller;

import com.localexperts.model.Business;
import com.localexperts.model.User;
import com.localexperts.repository.BusinessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// ── Reviews Controller ────────────────────────────────────────
// GET  /api/reviews/recent           — latest reviews across all businesses
// GET  /api/reviews/business/:id     — all reviews for a specific business
// POST /api/reviews/:reviewIndex/reply — owner replies to a review
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    @Autowired
    private BusinessRepository businessRepository;

    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentReviews(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "10") int limit) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<Business> businesses = city != null
                    ? businessRepository.findByCityAndStatus(city.toLowerCase(), "active")
                    : businessRepository.findByStatus("active");

            List<Map<String, Object>> allReviews = new ArrayList<>();
            for (Business b : businesses) {
                for (Business.Review r : b.getReviews()) {
                    Map<String, Object> reviewMap = new HashMap<>();
                    reviewMap.put("user", r.getUser());
                    reviewMap.put("name", r.getName());
                    reviewMap.put("rating", r.getRating());
                    reviewMap.put("comment", r.getComment());
                    reviewMap.put("date", r.getDate());
                    reviewMap.put("businessName", b.getName());
                    reviewMap.put("businessCity", b.getCity());
                    reviewMap.put("businessCategory", b.getCategory());
                    allReviews.add(reviewMap);
                }
            }

            allReviews.sort((a, b) -> {
                LocalDateTime da = (LocalDateTime) a.get("date");
                LocalDateTime db = (LocalDateTime) b.get("date");
                return db.compareTo(da);
            });

            List<Map<String, Object>> limited = allReviews.stream().limit(limit).collect(Collectors.toList());

            response.put("success", true);
            response.put("data", limited);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── GET /api/reviews/business/:id ─────────────────────────
    // Returns all reviews for a specific business (with id, reviewerName, etc.)
    @GetMapping("/business/{businessId}")
    public ResponseEntity<Map<String, Object>> getBusinessReviews(
            @PathVariable String businessId) {

        Map<String, Object> response = new HashMap<>();
        try {
            Optional<Business> opt = businessRepository.findById(businessId);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();
            List<Map<String, Object>> reviews = new ArrayList<>();

            for (int i = 0; i < business.getReviews().size(); i++) {
                Business.Review r = business.getReviews().get(i);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", businessId + "_" + i);          // composite id for reply
                map.put("reviewIndex", i);
                map.put("reviewerName", r.getName());
                map.put("user", r.getUser());
                map.put("rating", r.getRating());
                map.put("comment", r.getComment());
                map.put("createdAt", r.getDate());
                map.put("ownerReply", r.getOwnerReply());
                map.put("businessId", businessId);
                map.put("businessName", business.getName());
                reviews.add(map);
            }

            // Sort newest first
            reviews.sort((a, b) -> {
                LocalDateTime da = (LocalDateTime) a.get("createdAt");
                LocalDateTime db = (LocalDateTime) b.get("createdAt");
                if (da == null) return 1;
                if (db == null) return -1;
                return db.compareTo(da);
            });

            response.put("success", true);
            response.put("total", reviews.size());
            response.put("data", reviews);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── POST /api/reviews/:businessId/:reviewIndex/reply ──────
    // Business owner posts a reply to a specific review
    @PostMapping("/{businessId}/{reviewIndex}/reply")
    public ResponseEntity<Map<String, Object>> replyToReview(
            @PathVariable String businessId,
            @PathVariable int reviewIndex,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();
        User currentUser = (User) request.getAttribute("currentUser");

        if (currentUser == null) {
            response.put("success", false);
            response.put("message", "Authentication required");
            return ResponseEntity.status(401).body(response);
        }

        try {
            Optional<Business> opt = businessRepository.findById(businessId);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();

            // Only owner or admin can reply
            boolean isOwner = business.getOwner() != null && business.getOwner().equals(currentUser.getId());
            boolean isAdmin  = "admin".equals(currentUser.getRole());
            if (!isOwner && !isAdmin) {
                response.put("success", false);
                response.put("message", "Only the business owner can reply to reviews");
                return ResponseEntity.status(403).body(response);
            }

            if (reviewIndex < 0 || reviewIndex >= business.getReviews().size()) {
                response.put("success", false);
                response.put("message", "Review not found");
                return ResponseEntity.status(404).body(response);
            }

            String replyText = body.getOrDefault("reply", "").toString().trim();
            if (replyText.isEmpty()) {
                response.put("success", false);
                response.put("message", "Reply text cannot be empty");
                return ResponseEntity.badRequest().body(response);
            }

            business.getReviews().get(reviewIndex).setOwnerReply(replyText);
            businessRepository.save(business);

            response.put("success", true);
            response.put("message", "Reply posted successfully!");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

}