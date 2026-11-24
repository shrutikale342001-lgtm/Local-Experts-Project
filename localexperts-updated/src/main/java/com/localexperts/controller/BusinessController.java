package com.localexperts.controller;

import com.localexperts.model.Business;
import com.localexperts.model.User;
import com.localexperts.repository.BusinessRepository;
import com.localexperts.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// ── Business Controller ───────────────────────────────────────
// GET    /api/businesses              - get all (with filters)
// GET    /api/businesses/search       - search
// GET    /api/businesses/recommend    - AI-style recommendation
// GET    /api/businesses/:id          - get one
// POST   /api/businesses              - add new (auth required)
// PUT    /api/businesses/:id          - update (admin/owner)
// DELETE /api/businesses/:id          - delete (admin only)
// POST   /api/businesses/:id/reviews  - add review
// PUT    /api/businesses/:id/approve  - approve (admin only)

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private UserRepository userRepository;

    // ── GET /api/businesses/mine ──────────────────────────────
    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> getMyBusinesses(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        User currentUser = (User) request.getAttribute("currentUser");

        if (currentUser == null) {
            response.put("success", false);
            response.put("message", "Authentication required");
            return ResponseEntity.status(401).body(response);
        }

        try {
            List<Business> businesses = businessRepository.findByOwner(currentUser.getId());
            response.put("success", true);
            response.put("businesses", businesses);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Compute isOpen from hours string (real-time) ────────────
    private boolean computeIsOpen(String hours) {
        if (hours == null || hours.isBlank()) return false;
        String h = hours.trim()
            .replaceAll("(?i)\\s+to\\s+", " - ")
            .replaceAll("(?i)\\bam\\b", "AM")
            .replaceAll("(?i)\\bpm\\b", "PM");
        if (h.matches("(?i).*24\\s*hours?.*") || h.matches("(?i).*24\\s*x\\s*7.*")) return true;
        java.time.LocalTime now = java.time.LocalTime.now();
        int todayIdx = java.time.LocalDate.now().getDayOfWeek().getValue() % 7;
        String[] days = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
        java.util.regex.Matcher dayM = java.util.regex.Pattern
            .compile("([A-Za-z]{2,3})\\s*[-–]\\s*([A-Za-z]{2,3})").matcher(h);
        if (dayM.find()) {
            int s=-1, e=-1;
            for (int i=0;i<days.length;i++) {
                String g1=dayM.group(1).length()>=3?dayM.group(1).substring(0,3):dayM.group(1);
                String g2=dayM.group(2).length()>=3?dayM.group(2).substring(0,3):dayM.group(2);
                if (days[i].equalsIgnoreCase(g1)) s=i;
                if (days[i].equalsIgnoreCase(g2)) e=i;
            }
            if (s!=-1 && e!=-1) {
                boolean inRange = s<=e?(todayIdx>=s&&todayIdx<=e):(todayIdx>=s||todayIdx<=e);
                if (!inRange) return false;
            }
        }
        java.util.regex.Matcher timeM = java.util.regex.Pattern
            .compile("(\\d{1,2})[.:]?(\\d{0,2})\\s*(AM|PM)\\s*[-–]\\s*(\\d{1,2})[.:]?(\\d{0,2})\\s*(AM|PM)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(h);
        if (!timeM.find()) return false;
        int oh=Integer.parseInt(timeM.group(1)), om=timeM.group(2).isEmpty()?0:Integer.parseInt(timeM.group(2));
        int ch=Integer.parseInt(timeM.group(4)), cm=timeM.group(5).isEmpty()?0:Integer.parseInt(timeM.group(5));
        if (timeM.group(3).equalsIgnoreCase("PM")&&oh!=12) oh+=12;
        if (timeM.group(3).equalsIgnoreCase("AM")&&oh==12) oh=0;
        if (timeM.group(6).equalsIgnoreCase("PM")&&ch!=12) ch+=12;
        if (timeM.group(6).equalsIgnoreCase("AM")&&ch==12) ch=0;
        int openMin=oh*60+om, closeMin=ch*60+cm, nowMin=now.getHour()*60+now.getMinute();
        return closeMin>openMin?(nowMin>=openMin&&nowMin<closeMin):(nowMin>=openMin||nowMin<closeMin);
    }

    // ── GET /api/businesses ───────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllBusinesses(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minRating,
            @RequestParam(defaultValue = "rating") String sort,
            @RequestParam(defaultValue = "false") boolean admin,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<Business> businesses;

            // Admin can see ALL businesses (active + pending + rejected)
            boolean isAdmin = false;
            User currentUser = (User) request.getAttribute("currentUser");
            if (admin && currentUser != null && currentUser.getRole().equals("admin")) {
                isAdmin = true;
            }

            if (isAdmin) {
                // Return everything for admin
                businesses = (city != null)
                        ? businessRepository.findAll().stream()
                              .filter(b -> b.getCity().equals(city.toLowerCase()))
                              .collect(Collectors.toList())
                        : businessRepository.findAll();
            } else if (city != null && category != null) {
                businesses = businessRepository.findByCityAndCategoryAndStatus(city.toLowerCase(), category, "active");
            } else if (city != null) {
                businesses = businessRepository.findByCityAndStatus(city.toLowerCase(), "active");
            } else if (category != null) {
                businesses = businessRepository.findByCategoryAndStatus(category, "active");
            } else {
                businesses = businessRepository.findByStatus("active");
            }

            // Apply minRating filter
            if (minRating != null) {
                double finalMinRating = minRating;
                businesses = businesses.stream()
                        .filter(b -> b.getAvgRating() >= finalMinRating)
                        .collect(Collectors.toList());
            }

            // Sort
            switch (sort) {
                case "reviews" -> businesses.sort((a, b) -> b.getReviewCount() - a.getReviewCount());
                case "newest" -> businesses.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
                case "name" -> businesses.sort(Comparator.comparing(Business::getName));
                default -> businesses.sort((a, b) -> Double.compare(b.getAvgRating(), a.getAvgRating()));
            }

            // Recompute isOpen from hours string — never trust stale DB value
            businesses.forEach(b -> {
                if (b.getHours() != null && !b.getHours().isBlank())
                    b.setOpen(computeIsOpen(b.getHours()));
            });

            response.put("success", true);
            response.put("total", businesses.size());
            response.put("data", businesses);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── GET /api/businesses/search ────────────────────────────
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchBusinesses(
            @RequestParam String q,
            @RequestParam(required = false) String city) {

        Map<String, Object> response = new HashMap<>();
        try {
            if (q == null || q.trim().length() < 2) {
                response.put("success", false);
                response.put("message", "Query must be at least 2 characters");
                return ResponseEntity.badRequest().body(response);
            }

            List<Business> results;
            if (city != null) {
                results = businessRepository.searchByKeywordAndCity(city.toLowerCase(), q);
            } else {
                results = businessRepository.searchByKeyword(q);
            }

            // Increment search count
            results.forEach(b -> {
                b.setSearchCount(b.getSearchCount() + 1);
                businessRepository.save(b);
            });

            response.put("success", true);
            response.put("count", results.size());
            response.put("data", results);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── GET /api/businesses/recommend ─────────────────────────
    // Recommendation: Score = (Rating × 0.6) + (Popularity × 0.4)
    @GetMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String excludeId,
            @RequestParam(defaultValue = "5") int limit) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<Business> businesses;

            if (city != null) {
                businesses = businessRepository
                        .findByCityAndStatusAndAvgRatingGreaterThanEqualOrderByAvgRatingDesc(
                                city.toLowerCase(), "active", 4.0);
            } else {
                businesses = businessRepository
                        .findByStatusAndAvgRatingGreaterThanEqualOrderByAvgRatingDesc("active", 4.0);
            }

            // Filter by category and excludeId
            businesses = businesses.stream()
                    .filter(b -> category == null || b.getCategory().equals(category))
                    .filter(b -> excludeId == null || !b.getId().equals(excludeId))
                    .sorted((a, b2) -> Double.compare(b2.getRecommendScore(), a.getRecommendScore()))
                    .limit(limit)
                    .collect(Collectors.toList());

            // Add recommend score to response
            List<Map<String, Object>> scored = businesses.stream().map(b -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", b.getId());
                m.put("name", b.getName());
                m.put("category", b.getCategory());
                m.put("icon", b.getIcon());
                m.put("avgRating", b.getAvgRating());
                m.put("reviewCount", b.getReviewCount());
                m.put("isOpen", b.isOpen());
                m.put("recommendScore", b.getRecommendScore());
                return m;
            }).collect(Collectors.toList());

            response.put("success", true);
            response.put("algorithm", "Weighted score: 60% rating + 40% popularity (review count)");
            response.put("data", scored);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── GET /api/businesses/:id ───────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<Business> opt = businessRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();
            business.setViewCount(business.getViewCount() + 1);
            if (business.getHours() != null && !business.getHours().isBlank())
                business.setOpen(computeIsOpen(business.getHours()));
            businessRepository.save(business);

            response.put("success", true);
            response.put("data", business);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── POST /api/businesses ──────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> createBusiness(
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
            String name = (String) body.get("name");
            String category = (String) body.get("category");
            String description = (String) body.get("description");
            String city = (String) body.get("city");

            if (name == null || category == null || description == null || city == null) {
                response.put("success", false);
                response.put("message", "Name, category, description and city are required");
                return ResponseEntity.badRequest().body(response);
            }

            Business business = new Business();
            business.setName(name);
            business.setCategory(category);
            business.setDescription(description);
            business.setCity(city.toLowerCase());
            business.setOwner(currentUser.getId());
            business.setIcon(body.getOrDefault("icon", "🏪").toString());
            business.setHours(body.getOrDefault("hours", "Check with business").toString());

            // ── Save contact details (phone + owner email) ────
            if (body.containsKey("contact")) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> contactMap = (java.util.Map<String, String>) body.get("contact");
                    Business.Contact contact = new Business.Contact();
                    if (contactMap.containsKey("phone")) contact.setPhone(contactMap.get("phone"));
                    if (contactMap.containsKey("email")) contact.setEmail(contactMap.get("email"));
                    if (contactMap.containsKey("whatsapp")) contact.setWhatsapp(contactMap.get("whatsapp"));
                    business.setContact(contact);
                } catch (Exception ignored) {}
            }

            // ── Save address ──────────────────────────────────
            if (body.containsKey("address")) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> addrMap = (java.util.Map<String, String>) body.get("address");
                    Business.Address address = new Business.Address();
                    if (addrMap.containsKey("full")) address.setFull(addrMap.get("full"));
                    if (addrMap.containsKey("city")) address.setCity(addrMap.get("city"));
                    business.setAddress(address);
                } catch (Exception ignored) {}
            }

            // ── Save products / services with price ──────────
            if (body.containsKey("products")) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> products = (java.util.List<String>) body.get("products");
                    if (products != null) business.setProducts(products);
                } catch (Exception ignored) {}
            }

            // ── Save highlights ───────────────────────────────
            if (body.containsKey("highlights")) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> highlights = (java.util.List<String>) body.get("highlights");
                    if (highlights != null) business.setHighlights(highlights);
                } catch (Exception ignored) {}
            }

            // New business → pending by default, admin must approve
            // Only admin-created businesses go live immediately
            String creatorRole = currentUser.getRole();
            business.setStatus("admin".equals(creatorRole) ? "active" : "pending");

            businessRepository.save(business);

            // ── Upgrade user role to business_owner ───────────
            if (!"admin".equals(currentUser.getRole())) {
                currentUser.setRole("business_owner");
                userRepository.save(currentUser);
            }

            String msg = "admin".equals(creatorRole)
                ? "Business listed successfully!"
                : "Business submitted! It will go live after admin approval.";

            response.put("success", true);
            response.put("message", msg);
            response.put("data", business);
            return ResponseEntity.status(201).body(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── PUT /api/businesses/:id ───────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateBusiness(
            @PathVariable String id,
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
            Optional<Business> opt = businessRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();

            // Only admin or owner can update
            if (!currentUser.getRole().equals("admin") &&
                !currentUser.getId().equals(business.getOwner())) {
                response.put("success", false);
                response.put("message", "Not authorized to update this business");
                return ResponseEntity.status(403).body(response);
            }

            if (body.containsKey("name")) business.setName((String) body.get("name"));
            if (body.containsKey("description")) business.setDescription((String) body.get("description"));
            if (body.containsKey("hours")) business.setHours((String) body.get("hours"));
            if (body.containsKey("isOpen")) business.setOpen((Boolean) body.get("isOpen"));
            if (body.containsKey("status")) business.setStatus((String) body.get("status"));
            if (body.containsKey("products")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> products = (java.util.List<String>) body.get("products");
                business.setProducts(products);
            }
            if (body.containsKey("highlights")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> highlights = (java.util.List<String>) body.get("highlights");
                business.setHighlights(highlights);
            }
            business.setUpdatedAt(LocalDateTime.now());

            businessRepository.save(business);

            response.put("success", true);
            response.put("message", "Business updated successfully");
            response.put("data", business);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── DELETE /api/businesses/:id (Admin or Owner) ───────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteBusiness(
            @PathVariable String id,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();
        User currentUser = (User) request.getAttribute("currentUser");

        if (currentUser == null) {
            response.put("success", false);
            response.put("message", "Authentication required");
            return ResponseEntity.status(401).body(response);
        }

        try {
            Optional<Business> opt = businessRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();

            // Admin OR owner can delete
            if (!currentUser.getRole().equals("admin") &&
                !currentUser.getId().equals(business.getOwner())) {
                response.put("success", false);
                response.put("message", "Not authorized to delete this business");
                return ResponseEntity.status(403).body(response);
            }

            businessRepository.deleteById(id);
            response.put("success", true);
            response.put("message", "Business deleted successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── POST /api/businesses/:id/reviews ──────────────────────
    @PostMapping("/{id}/reviews")
    public ResponseEntity<Map<String, Object>> addReview(
            @PathVariable String id,
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
            int rating = Integer.parseInt(body.get("rating").toString());
            if (rating < 1 || rating > 5) {
                response.put("success", false);
                response.put("message", "Rating must be between 1 and 5");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<Business> opt = businessRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();

            // Check if already reviewed
            boolean alreadyReviewed = business.getReviews().stream()
                    .anyMatch(r -> r.getUser().equals(currentUser.getId()));
            if (alreadyReviewed) {
                response.put("success", false);
                response.put("message", "You have already reviewed this business");
                return ResponseEntity.badRequest().body(response);
            }

            Business.Review review = new Business.Review();
            review.setUser(currentUser.getId());
            review.setName(currentUser.getName());
            review.setRating(rating);
            review.setComment(body.getOrDefault("comment", "").toString());

            business.getReviews().add(review);
            business.updateRating();
            businessRepository.save(business);

            response.put("success", true);
            response.put("message", "Review added successfully!");
            response.put("avgRating", business.getAvgRating());
            response.put("reviewCount", business.getReviewCount());
            return ResponseEntity.status(201).body(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── PUT /api/businesses/:id/approve (Admin only) ──────────
    @PutMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveBusiness(
            @PathVariable String id,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();
        User currentUser = (User) request.getAttribute("currentUser");

        if (currentUser == null || !currentUser.getRole().equals("admin")) {
            response.put("success", false);
            response.put("message", "Admin access required");
            return ResponseEntity.status(403).body(response);
        }

        try {
            Optional<Business> opt = businessRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Business not found");
                return ResponseEntity.status(404).body(response);
            }

            Business business = opt.get();
            business.setStatus("active");
            business.setVerified(true);
            businessRepository.save(business);

            response.put("success", true);
            response.put("message", "Business approved and verified!");
            response.put("data", business);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
