package com.localexperts.service;

import com.localexperts.model.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;

// ── Order Email Service ───────────────────────────────────────
// Sends two emails on every new order:
//   1. To Business OWNER  → "New Order / Booking Received"
//   2. To CUSTOMER        → "Your Order is Confirmed"
@Service
public class OrderEmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    // ── Entry point: send both emails ─────────────────────────
    public void sendOrderEmails(Order order) {
        try {
            sendOwnerEmail(order);
        } catch (Exception e) {
            System.err.println("❌ Owner email failed: " + e.getMessage());
        }
        try {
            sendCustomerEmail(order);
        } catch (Exception e) {
            System.err.println("❌ Customer email failed: " + e.getMessage());
        }
    }

    // ── Entry point: send receipt email to customer after payment ──
    public void sendReceiptEmail(Order order) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(order.getCustomerEmail());
            helper.setSubject("🧾 Your Receipt — " + order.getBusinessName() + " #" + order.getId().substring(Math.max(0, order.getId().length()-8)).toUpperCase());
            helper.setText(buildReceiptEmailHtml(order), true);
            mailSender.send(msg);
            System.out.println("✅ Receipt email sent → " + order.getCustomerEmail());
        } catch (Exception e) {
            System.err.println("❌ Receipt email failed: " + e.getMessage());
        }
    }

    // ── Receipt Email HTML (with payment info) ────────────────
    private String buildReceiptEmailHtml(Order order) {
        String payMethod = order.getPaymentMethod();
        String payLabel  = "online".equals(payMethod)  ? "✅ Paid Online (UPI)"
                         : "offline".equals(payMethod) ? "💵 Cash / Pay at Location"
                         : "—";
        String payBg     = "online".equals(payMethod)  ? "#e8f5e9"
                         : "offline".equals(payMethod) ? "#fff8e1"
                         : "#f5f5f5";
        String payColor  = "online".equals(payMethod)  ? "#2e7d32"
                         : "offline".equals(payMethod) ? "#e65100"
                         : "#666";

        String amountRow = (order.getTotalAmount() != null && order.getTotalAmount() > 0)
            ? "<tr style='background:#6c63ff11;'><td style='padding:11px 14px;font-weight:800;font-size:15px;'>💰 Total Amount</td>"
              + "<td style='padding:11px 14px;font-weight:900;font-size:20px;color:#6c63ff;'>₹" + String.format("%.0f", order.getTotalAmount()) + "</td></tr>"
            : "";

        String shortId = order.getId().substring(Math.max(0, order.getId().length()-8)).toUpperCase();
        String createdAt = order.getCreatedAt() != null
            ? order.getCreatedAt().toLocalDate().toString()
            : java.time.LocalDate.now().toString();

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'/></head>"
            + "<body style='font-family:Arial,sans-serif;background:#f4f4f8;margin:0;padding:20px;'>"
            + "<div style='max-width:560px;margin:auto;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.1);'>"
            // Header
            + "<div style='background:linear-gradient(135deg,#6c63ff,#a29bfe);padding:28px;text-align:center;'>"
            + "<div style='font-size:13px;font-weight:700;color:rgba(255,255,255,0.8);letter-spacing:1px;margin-bottom:6px;'>🛒 LOCALEXPERTS+</div>"
            + "<h2 style='color:#fff;margin:0;font-size:22px;'>🧾 Order Receipt</h2>"
            + "<p style='color:rgba(255,255,255,0.75);margin:6px 0 0;font-size:13px;'>" + createdAt + "</p>"
            + "</div>"
            // Business bar
            + "<div style='background:#6c63ff10;padding:16px 24px;border-bottom:1px solid #e8e8f0;'>"
            + "<div style='font-size:17px;font-weight:800;color:#2d2d6e;'>" + order.getBusinessName() + "</div>"
            + "<div style='font-size:12px;color:#888;margin-top:2px;'>" + getTypeName(order.getBusinessCategory()) + "</div>"
            + "</div>"
            // Body
            + "<div style='padding:24px;'>"
            + "<p style='font-size:15px;color:#333;margin-bottom:16px;'>Hi <b>" + order.getCustomerName() + "</b>, your order has been confirmed! 🎉</p>"
            + "<table style='width:100%;border-collapse:collapse;margin-bottom:16px;font-size:13px;'>"
            + "<tr><td style='padding:9px 12px;font-weight:700;color:#555;width:42%;background:#f8f8ff;'>Order ID</td>"
            + "<td style='padding:9px 12px;font-weight:800;'>#" + shortId + "</td></tr>"
            + "<tr><td style='padding:9px 12px;font-weight:700;color:#555;'>Customer</td>"
            + "<td style='padding:9px 12px;'>" + order.getCustomerName() + "</td></tr>"
            + "<tr style='background:#f8f8ff;'><td style='padding:9px 12px;font-weight:700;color:#555;'>Phone</td>"
            + "<td style='padding:9px 12px;'>" + (order.getCustomerPhone() != null ? order.getCustomerPhone() : "—") + "</td></tr>"
            + buildExtraFieldsTable(order)
            + (order.getNotes() != null && !order.getNotes().isEmpty()
                ? "<tr><td style='padding:9px 12px;font-weight:700;color:#555;background:#f8f8ff;'>Notes</td><td style='padding:9px 12px;'>" + order.getNotes() + "</td></tr>" : "")
            + "</table>"
            // Payment section
            + "<div style='background:#fafafa;border:1px solid #eee;border-radius:10px;padding:16px;margin-bottom:16px;'>"
            + "<div style='font-size:10px;font-weight:800;letter-spacing:2px;color:#aaa;text-transform:uppercase;margin-bottom:10px;'>Payment</div>"
            + "<table style='width:100%;border-collapse:collapse;font-size:13px;'>"
            + "<tr><td style='padding:8px 0;font-weight:700;color:#555;width:42%;'>Payment Mode</td>"
            + "<td style='padding:8px 0;'><span style='background:" + payBg + ";color:" + payColor + ";padding:4px 12px;border-radius:100px;font-size:12px;font-weight:700;'>" + payLabel + "</span></td></tr>"
            + amountRow
            + "</table>"
            + "</div>"
            + "<p style='color:#888;font-size:12px;line-height:1.6;'>The business owner will contact you shortly. For any queries, reply to this email.</p>"
            + "</div>"
            // Footer
            + "<div style='background:#f8f8ff;border-top:1px solid #e8e8f0;padding:16px 24px;text-align:center;font-size:12px;color:#aaa;'>"
            + "Thank you for using <b>LocalExperts+</b>!<br/>This is a computer-generated receipt."
            + "</div>"
            + "</div></body></html>";
    }

    // ── 1. Email to Business Owner ────────────────────────────
    private void sendOwnerEmail(Order order) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(order.getOwnerEmail());
        helper.setSubject("🔔 New " + getTypeName(order.getBusinessCategory())
                + " Order — " + order.getBusinessName());
        helper.setText(buildOwnerEmailHtml(order), true);

        mailSender.send(msg);
        System.out.println("✅ Owner email sent → " + order.getOwnerEmail());
    }

    // ── 2. Email to Customer ──────────────────────────────────
    private void sendCustomerEmail(Order order) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(order.getCustomerEmail());
        helper.setSubject("✅ Your " + getTypeName(order.getBusinessCategory())
                + " Booking is Confirmed — " + order.getBusinessName());
        helper.setText(buildCustomerEmailHtml(order), true);

        mailSender.send(msg);
        System.out.println("✅ Customer email sent → " + order.getCustomerEmail());
    }


    // ── Entry point: send delivery confirmation emails ────────
    public void sendDeliveredEmails(Order order) {
        try {
            sendOwnerDeliveredEmail(order);
        } catch (Exception e) {
            System.err.println("❌ Owner delivery email failed: " + e.getMessage());
        }
        try {
            sendCustomerDeliveredEmail(order);
        } catch (Exception e) {
            System.err.println("❌ Customer delivery email failed: " + e.getMessage());
        }
    }

    // ── 3. Delivery confirmation to Owner ─────────────────────
    private void sendOwnerDeliveredEmail(Order order) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(order.getOwnerEmail());
        helper.setSubject("✅ Order Delivered — " + order.getBusinessName() + " #" + order.getId().substring(Math.max(0,order.getId().length()-8)).toUpperCase());
        helper.setText(buildOwnerDeliveredHtml(order), true);
        mailSender.send(msg);
        System.out.println("✅ Owner delivery email sent → " + order.getOwnerEmail());
    }

    // ── 4. Delivery confirmation to Customer ──────────────────
    private void sendCustomerDeliveredEmail(Order order) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(order.getCustomerEmail());
        helper.setSubject("📦 Order Delivered — Thank you for using LocalExperts+!");
        helper.setText(buildCustomerDeliveredHtml(order), true);
        mailSender.send(msg);
        System.out.println("✅ Customer delivery email sent → " + order.getCustomerEmail());
    }

    // ── Owner Delivered Email HTML ────────────────────────────
    private String buildOwnerDeliveredHtml(Order order) {
        String amountRow = (order.getTotalAmount() != null && order.getTotalAmount() > 0)
            ? "<tr style='background:#00b89411;'><td style='padding:10px;font-weight:bold;'>Total Amount</td><td style='padding:10px;font-weight:800;font-size:16px;color:#00b894;'>₹" + String.format("%.0f", order.getTotalAmount()) + "</td></tr>"
            : "";
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;max-width:600px;margin:auto;'>"
            + "<div style='background:#00b894;padding:20px;border-radius:12px 12px 0 0;text-align:center;'>"
            + "<h2 style='color:#fff;margin:0;'>✅ Order Delivered!</h2></div>"
            + "<div style='background:#f9f9f9;padding:24px;border-radius:0 0 12px 12px;'>"
            + "<h3 style='color:#333;'>Business: " + order.getBusinessName() + "</h3>"
            + "<p style='color:#666;'>The customer <b>" + order.getCustomerName() + "</b> has confirmed delivery of their order.</p>"
            + "<table style='width:100%;border-collapse:collapse;margin:16px 0;'>"
            + "<tr><td style='padding:10px;font-weight:bold;width:40%;'>Order ID</td><td style='padding:10px;'>#" + order.getId() + "</td></tr>"
            + "<tr style='background:#f0f0f0;'><td style='padding:10px;font-weight:bold;'>Customer</td><td style='padding:10px;'>" + order.getCustomerName() + " · " + order.getCustomerEmail() + "</td></tr>"
            + amountRow
            + "</table>"
            + "<p style='color:#888;font-size:13px;margin-top:20px;'>Transaction complete. Thank you for being part of LocalExperts+!</p>"
            + "<p style='color:#888;font-size:12px;'>— LocalExperts+ Team</p>"
            + "</div></body></html>";
    }

    // ── Customer Delivered Email HTML ─────────────────────────
    private String buildCustomerDeliveredHtml(Order order) {
        String amountRow = (order.getTotalAmount() != null && order.getTotalAmount() > 0)
            ? "<tr style='background:#00b89411;'><td style='padding:10px;font-weight:bold;'>Total Paid</td><td style='padding:10px;font-weight:800;font-size:16px;color:#00b894;'>₹" + String.format("%.0f", order.getTotalAmount()) + "</td></tr>"
            : "";
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;max-width:600px;margin:auto;'>"
            + "<div style='background:#00b894;padding:20px;border-radius:12px 12px 0 0;text-align:center;'>"
            + "<h2 style='color:#fff;margin:0;'>📦 Order Delivered! Thank You!</h2></div>"
            + "<div style='background:#f9f9f9;padding:24px;border-radius:0 0 12px 12px;'>"
            + "<p style='color:#333;font-size:16px;'>Hi <b>" + order.getCustomerName() + "</b>,</p>"
            + "<p style='color:#555;'>Your order from <b>" + order.getBusinessName() + "</b> has been marked as delivered. We hope you had a great experience! 🎉</p>"
            + "<table style='width:100%;border-collapse:collapse;margin:16px 0;'>"
            + "<tr><td style='padding:10px;font-weight:bold;width:40%;'>Order ID</td><td style='padding:10px;'>#" + order.getId() + "</td></tr>"
            + "<tr style='background:#f0f0f0;'><td style='padding:10px;font-weight:bold;'>Business</td><td style='padding:10px;'>" + order.getBusinessName() + "</td></tr>"
            + amountRow
            + "</table>"
            + "<p style='color:#555;'>Please share your experience by leaving a review on LocalExperts+!</p>"
            + "<p style='color:#888;font-size:12px;margin-top:20px;'>Thank you for using LocalExperts+!</p>"
            + "</div></body></html>";
    }

    // ── Owner Email HTML ──────────────────────────────────────
    private String buildOwnerEmailHtml(Order order) {
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;max-width:600px;margin:auto;'>"
            + "<div style='background:#6c63ff;padding:20px;border-radius:12px 12px 0 0;text-align:center;'>"
            + "<h2 style='color:#fff;margin:0;'>🔔 New Booking Received!</h2></div>"
            + "<div style='background:#f9f9f9;padding:24px;border-radius:0 0 12px 12px;'>"
            + "<h3 style='color:#333;'>Business: " + order.getBusinessName() + "</h3>"
            + "<p style='color:#666;'>A new <b>" + getTypeName(order.getBusinessCategory())
            + "</b> order has been placed. Details below:</p>"
            + "<table style='width:100%;border-collapse:collapse;margin:16px 0;'>"
            + "<tr style='background:#6c63ff11;'><td style='padding:10px;font-weight:bold;width:40%;'>Order ID</td>"
            + "<td style='padding:10px;'>#" + order.getId() + "</td></tr>"
            + "<tr><td style='padding:10px;font-weight:bold;'>Customer Name</td>"
            + "<td style='padding:10px;'>" + order.getCustomerName() + "</td></tr>"
            + "<tr style='background:#6c63ff11;'><td style='padding:10px;font-weight:bold;'>Customer Email</td>"
            + "<td style='padding:10px;'>" + order.getCustomerEmail() + "</td></tr>"
            + "<tr><td style='padding:10px;font-weight:bold;'>Customer Phone</td>"
            + "<td style='padding:10px;'>" + order.getCustomerPhone() + "</td></tr>"
            + buildExtraFieldsTable(order)
            + (order.getNotes() != null && !order.getNotes().isEmpty()
                ? "<tr><td style='padding:10px;font-weight:bold;'>Special Notes</td>"
                  + "<td style='padding:10px;'>" + order.getNotes() + "</td></tr>" : "")
            + "</table>"
            + "<p style='color:#888;font-size:13px;margin-top:20px;'>Please confirm or contact the customer promptly.</p>"
            + "<p style='color:#888;font-size:12px;'>— LocalExperts+ Team</p>"
            + "</div></body></html>";
    }

    // ── Customer Email HTML ───────────────────────────────────
    private String buildCustomerEmailHtml(Order order) {
        String shortId = order.getId().substring(Math.max(0, order.getId().length()-8)).toUpperCase();
        String amountRow = (order.getTotalAmount() != null && order.getTotalAmount() > 0)
            ? "<tr style='background:#00b89411;'><td style='padding:10px;font-weight:800;'>💰 Total Amount</td>"
              + "<td style='padding:10px;font-weight:800;font-size:16px;color:#00b894;'>₹" + String.format("%.0f", order.getTotalAmount()) + "</td></tr>"
            : "";
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'/></head><body style='font-family:Arial,sans-serif;max-width:600px;margin:auto;'>"
            + "<div style='background:#00b894;padding:20px;border-radius:12px 12px 0 0;text-align:center;'>"
            + "<div style='font-size:12px;font-weight:700;color:rgba(255,255,255,0.8);margin-bottom:4px;'>🛒 LOCALEXPERTS+</div>"
            + "<h2 style='color:#fff;margin:0;'>✅ Booking Confirmed!</h2></div>"
            + "<div style='background:#f9f9f9;padding:24px;border-radius:0 0 12px 12px;'>"
            + "<p style='color:#333;font-size:16px;'>Hi <b>" + order.getCustomerName() + "</b>,</p>"
            + "<p style='color:#555;'>Your <b>" + getTypeName(order.getBusinessCategory())
            + "</b> booking at <b>" + order.getBusinessName() + "</b> has been received successfully! 🎉</p>"
            + "<table style='width:100%;border-collapse:collapse;margin:16px 0;'>"
            + "<tr style='background:#00b89411;'><td style='padding:10px;font-weight:bold;width:40%;'>Order ID</td>"
            + "<td style='padding:10px;font-weight:800;'>#" + shortId + "</td></tr>"
            + buildExtraFieldsTable(order)
            + (order.getNotes() != null && !order.getNotes().isEmpty()
                ? "<tr><td style='padding:10px;font-weight:bold;'>Your Notes</td>"
                  + "<td style='padding:10px;'>" + order.getNotes() + "</td></tr>" : "")
            + amountRow
            + "</table>"
            + "<p style='color:#555;'>The business owner will contact you shortly to confirm.</p>"
            + "<p style='color:#888;font-size:12px;'>Once payment is completed, you will receive a full receipt by email.</p>"
            + "<p style='color:#888;font-size:12px;margin-top:20px;'>Thank you for using LocalExperts+!</p>"
            + "</div></body></html>";
    }

    // ── Build extra fields table rows ─────────────────────────
    private String buildExtraFieldsTable(Order order) {
        if (order.getExtraFields() == null || order.getExtraFields().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean alternate = false;
        for (var entry : order.getExtraFields().entrySet()) {
            String bg = alternate ? "background:#f0f0f0;" : "";
            sb.append("<tr style='" + bg + "'><td style='padding:10px;font-weight:bold;'>")
              .append(formatKey(entry.getKey()))
              .append("</td><td style='padding:10px;'>")
              .append(entry.getValue())
              .append("</td></tr>");
            alternate = !alternate;
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────
    private String getTypeName(String category) {
        if (category == null) return "Service";
        return switch (category.toLowerCase()) {
            case "hotels"     -> "Hotel / Room";
            case "food"       -> "Food";
            case "healthcare" -> "Healthcare / Appointment";
            case "services"   -> "Service";
            case "grocery"    -> "Grocery";
            case "education"  -> "Education / Admission";
            default           -> category;
        };
    }

    private String formatKey(String key) {
        // camelCase → Title Case with spaces
        return key.replaceAll("([A-Z])", " $1")
                  .replaceAll("^.", String.valueOf(Character.toUpperCase(key.charAt(0))))
                  .trim();
    }
}
