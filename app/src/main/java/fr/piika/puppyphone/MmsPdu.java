package fr.piika.puppyphone;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encodage / décodage minimal des PDU MMS (OMA MMS Encapsulation 1.2, encodage binaire WSP).
 * - M-Send.req     : pour envoyer un MMS (texte + photo / vidéo / PDF…)
 * - M-Notification.ind : avis reçu par WAP push → adresse de téléchargement
 * - M-Retrieve.conf : le MMS téléchargé → expéditeur, date, pièces
 */
public final class MmsPdu {
    private MmsPdu() { }

    // en-têtes (code | 0x80)
    static final int H_BCC = 0x81, H_CC = 0x82, H_CONTENT_LOCATION = 0x83, H_CONTENT_TYPE = 0x84, H_DATE = 0x85,
            H_DELIVERY_REPORT = 0x86, H_EXPIRY = 0x88, H_FROM = 0x89, H_MESSAGE_CLASS = 0x8A, H_MESSAGE_ID = 0x8B,
            H_MESSAGE_TYPE = 0x8C, H_MMS_VERSION = 0x8D, H_MESSAGE_SIZE = 0x8E, H_READ_REPORT = 0x90,
            H_SUBJECT = 0x96, H_TO = 0x97, H_TRANSACTION_ID = 0x98;
    static final int MT_SEND_REQ = 0x80, MT_NOTIFICATION_IND = 0x82, MT_RETRIEVE_CONF = 0x84;

    public static class Part {
        public String mime, name, text;
        public byte[] data;
        public Part(String mime, String name, byte[] data) { this.mime = mime; this.name = name; this.data = data; }
    }

    public static class Message {
        public int type;
        public String from = "", subject = "", transactionId = "", contentLocation = "";
        public long date, size;
        public final List<String> to = new ArrayList<>();
        public final List<Part> parts = new ArrayList<>();
    }

    // ------------------------------------------------------------------ outils d'écriture
    static void uintvar(ByteArrayOutputStream o, long v) {
        byte[] tmp = new byte[10];
        int n = 0;
        tmp[n++] = (byte) (v & 0x7F);
        v >>>= 7;
        while (v > 0) { tmp[n++] = (byte) (0x80 | (v & 0x7F)); v >>>= 7; }
        for (int i = n - 1; i >= 0; i--) o.write(tmp[i]);
    }

    static void text(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0 && (b[0] & 0xFF) >= 0x80) o.write(0x7F); // quote
        o.write(b, 0, b.length);
        o.write(0);
    }

    static void valueLength(ByteArrayOutputStream o, int len) {
        if (len < 31) o.write(len);
        else { o.write(31); uintvar(o, len); }
    }

    /** Encoded-string en UTF-8 : longueur + charset utf-8 (106) + texte. */
    static void encodedString(ByteArrayOutputStream o, String s) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(0xEA); // 106 = UTF-8, entier court
        text(t, s);
        valueLength(o, t.size());
        o.write(t.toByteArray(), 0, t.size());
    }

    static String toAddress(String num) {
        String n = num.replaceAll("[^0-9+*#]", "");
        if (n.contains("@")) return num;
        return n + "/TYPE=PLMN";
    }

    static void contentType(ByteArrayOutputStream o, String mime, String name, boolean utf8) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        int wk = wellKnown(mime);
        if (wk >= 0) t.write(0x80 | wk); else text(t, mime);
        if (utf8) { t.write(0x81); t.write(0xEA); } // charset=utf-8
        if (name != null) { t.write(0x85); text(t, name); } // name
        valueLength(o, t.size());
        o.write(t.toByteArray(), 0, t.size());
    }

    static final String[] WK = new String[0x34];
    static {
        WK[0x00] = "*/*"; WK[0x01] = "text/*"; WK[0x02] = "text/html"; WK[0x03] = "text/plain"; WK[0x07] = "text/x-vcard";
        WK[0x08] = "text/vnd.wap.wml"; WK[0x1C] = "image/*"; WK[0x1D] = "image/gif"; WK[0x1E] = "image/jpeg"; WK[0x1F] = "image/tiff";
        WK[0x20] = "image/png"; WK[0x21] = "image/vnd.wap.wbmp"; WK[0x22] = "application/vnd.wap.multipart.*";
        WK[0x23] = "application/vnd.wap.multipart.mixed"; WK[0x26] = "application/vnd.wap.multipart.alternative";
        WK[0x33] = "application/vnd.wap.multipart.related";
    }

    static int wellKnown(String mime) {
        if (mime == null) return -1;
        for (int i = 0; i < WK.length; i++) if (mime.equalsIgnoreCase(WK[i])) return i;
        return -1;
    }

    // ------------------------------------------------------------------ M-Send.req
    public static byte[] buildSendReq(List<String> recipients, String subject, List<Part> parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(H_MESSAGE_TYPE); o.write(MT_SEND_REQ);
        o.write(H_TRANSACTION_ID); text(o, "T" + Long.toHexString(System.nanoTime()));
        o.write(H_MMS_VERSION); o.write(0x92); // 1.2
        o.write(H_FROM); o.write(1); o.write(0x81); // insert-address-token : l'opérateur met notre numéro
        for (String r : recipients) { o.write(H_TO); encodedString(o, toAddress(r)); }
        if (subject != null && !subject.isEmpty()) { o.write(H_SUBJECT); encodedString(o, subject); }
        o.write(H_MESSAGE_CLASS); o.write(0x80); // personal
        o.write(H_EXPIRY); o.write(5); o.write(0x81); o.write(3); o.write(0x09); o.write(0x3A); o.write(0x80); // relatif 7 jours
        o.write(H_DELIVERY_REPORT); o.write(0x81);
        o.write(H_READ_REPORT); o.write(0x81);

        // SMIL pour un bel affichage chez le destinataire
        StringBuilder smil = new StringBuilder("<smil><head><layout><root-layout/><region id=\"Image\" top=\"0\" left=\"0\" height=\"70%\" width=\"100%\" fit=\"meet\"/>"
                + "<region id=\"Text\" top=\"70%\" left=\"0\" height=\"30%\" width=\"100%\" fit=\"scroll\"/></layout></head><body>");
        for (Part p : parts) {
            smil.append("<par dur=\"5000ms\">");
            if (p.mime.startsWith("image/")) smil.append("<img src=\"").append(p.name).append("\" region=\"Image\"/>");
            else if (p.mime.startsWith("video/")) smil.append("<video src=\"").append(p.name).append("\" region=\"Image\"/>");
            else if (p.mime.startsWith("audio/")) smil.append("<audio src=\"").append(p.name).append("\"/>");
            else if (p.mime.startsWith("text/plain")) smil.append("<text src=\"").append(p.name).append("\" region=\"Text\"/>");
            else smil.append("<ref src=\"").append(p.name).append("\"/>");
            smil.append("</par>");
        }
        smil.append("</body></smil>");
        List<Part> all = new ArrayList<>();
        all.add(new Part("application/smil", "smil.xml", smil.toString().getBytes(StandardCharsets.UTF_8)));
        all.addAll(parts);

        // Content-Type : multipart/related ; type=application/smil ; start=<smil>
        o.write(H_CONTENT_TYPE);
        ByteArrayOutputStream ct = new ByteArrayOutputStream();
        ct.write(0x80 | 0x33);
        ct.write(0x89); text(ct, "application/smil");
        ct.write(0x8A); text(ct, "<smil>");
        valueLength(o, ct.size());
        o.write(ct.toByteArray(), 0, ct.size());

        // corps multipart
        uintvar(o, all.size());
        for (Part p : all) {
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            boolean isText = p.mime.startsWith("text/") || p.mime.equals("application/smil");
            contentType(h, p.mime, p.name, isText);
            String cid = p.mime.equals("application/smil") ? "smil" : p.name;
            byte[] cidB = ("<" + cid + ">").getBytes(StandardCharsets.UTF_8);
            h.write(0xC0); h.write(0x22); h.write(cidB, 0, cidB.length); h.write(0); // Content-ID
            h.write(0x8E); text(h, p.name); // Content-Location
            uintvar(o, h.size());
            uintvar(o, p.data.length);
            o.write(h.toByteArray(), 0, h.size());
            o.write(p.data, 0, p.data.length);
        }
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ lecture
    static class Reader {
        final byte[] b;
        int i;
        Reader(byte[] b) { this.b = b; }
        boolean more() { return i < b.length; }
        int peek() { return b[i] & 0xFF; }
        int u8() { return b[i++] & 0xFF; }
        long uintvar() { long v = 0; int c; do { c = u8(); v = (v << 7) | (c & 0x7F); } while ((c & 0x80) != 0 && more()); return v; }
        String text() {
            int s = i;
            if (peek() == 0x7F || peek() == 0x22) { i++; s = i; }
            while (more() && b[i] != 0) i++;
            String r = new String(b, s, i - s, StandardCharsets.UTF_8);
            if (more()) i++;
            return r;
        }
        int valueLengthOrShort() {
            int c = peek();
            if (c < 31) { i++; return c; }
            if (c == 31) { i++; return (int) uintvar(); }
            return -1;
        }
        long longInt() {
            int c = peek();
            if (c >= 0x80) { i++; return c & 0x7F; }
            int n = u8();
            long v = 0;
            for (int k = 0; k < n && more(); k++) v = (v << 8) | u8();
            return v;
        }
        String encodedString() {
            int c = peek();
            if (c < 32) {
                int len = valueLengthOrShort();
                int end = i + len;
                if (more() && peek() >= 0x80) i++; else if (more() && peek() < 31) { int l = u8(); i += l; }
                String s = i < end ? text() : "";
                i = Math.max(i, end);
                return s;
            }
            return text();
        }
        void skipValue() {
            int c = peek();
            if (c >= 0x80) { i++; return; }
            if (c <= 30) { i += 1 + c; return; }
            if (c == 31) { i++; long l = uintvar(); i += (int) l; return; }
            text();
        }
        /** Content-Type : renvoie le type MIME et avance après la valeur. */
        String contentType(String[] nameOut) {
            int c = peek();
            if (c >= 0x80) { i++; return wk(c & 0x7F); }
            if (c >= 32) return text();
            int len = valueLengthOrShort();
            int end = i + len;
            String mime;
            if (peek() >= 0x80) mime = wk(u8() & 0x7F);
            else if (peek() >= 32) mime = text();
            else { i = end; return "application/octet-stream"; }
            while (i < end) {
                int p = u8();
                if (p == 0x85 || p == 0x97 || p == 0x86 || p == 0x98) { String n = text(); if (nameOut != null && nameOut[0] == null) nameOut[0] = n; }
                else if (p == 0x81) { if (peek() >= 0x80) i++; else skipValue(); }
                else if (p >= 0x80) skipValue();
                else { i--; text(); if (i < end) skipValue(); }
            }
            i = end;
            return mime;
        }
        String wk(int c) { return c < WK.length && WK[c] != null ? WK[c] : "application/octet-stream"; }
    }

    static String cleanAddr(String a) {
        if (a == null) return "";
        int s = a.indexOf("/TYPE");
        return (s > 0 ? a.substring(0, s) : a).trim();
    }

    public static Message parse(byte[] pdu) {
        Message m = new Message();
        Reader r = new Reader(pdu);
        try {
            while (r.more()) {
                int h = r.u8();
                if (h == H_CONTENT_TYPE) {
                    r.contentType(null);
                    parseBody(r, m);
                    break;
                }
                switch (h) {
                    case H_MESSAGE_TYPE: m.type = r.u8(); break;
                    case H_TRANSACTION_ID: m.transactionId = r.text(); break;
                    case H_CONTENT_LOCATION: m.contentLocation = r.text(); break;
                    case H_DATE: m.date = r.longInt(); break;
                    case H_MESSAGE_SIZE: m.size = r.longInt(); break;
                    case H_SUBJECT: m.subject = r.encodedString(); break;
                    case H_TO: m.to.add(cleanAddr(r.encodedString())); break;
                    case H_FROM: {
                        int len = r.valueLengthOrShort();
                        int end = r.i + len;
                        int tok = r.u8();
                        if (tok == 0x80 && r.i < end) m.from = cleanAddr(r.encodedString());
                        r.i = end;
                        break;
                    }
                    default:
                        if (h >= 0x80) r.skipValue(); else return m;
                }
            }
        } catch (Exception ignored) { }
        return m;
    }

    static void parseBody(Reader r, Message m) {
        if (!r.more()) return;
        long n = r.uintvar();
        for (int k = 0; k < n && r.more(); k++) {
            int hl = (int) r.uintvar();
            int dl = (int) r.uintvar();
            int hs = r.i;
            String[] name = new String[1];
            String mime = r.contentType(name);
            int he = hs + hl;
            String loc = null;
            while (r.i < he) {
                int h = r.u8();
                if (h == 0x8E) loc = r.text();
                else if (h == 0xC0) r.text();
                else if (h >= 0x80) r.skipValue();
                else { r.i--; r.text(); if (r.i < he) r.text(); }
            }
            r.i = he;
            byte[] data = new byte[Math.max(0, Math.min(dl, r.b.length - r.i))];
            System.arraycopy(r.b, r.i, data, 0, data.length);
            r.i += dl;
            String nm = name[0] != null ? name[0] : loc != null ? loc : "piece" + k;
            String ml = mime.toLowerCase(Locale.ROOT);
            if (ml.startsWith("application/vnd.wap.multipart")) {
                Reader sub = new Reader(data);
                parseBody(sub, m);
                continue;
            }
            Part p = new Part(mime, nm, data);
            if (ml.startsWith("text/plain")) p.text = new String(data, StandardCharsets.UTF_8);
            m.parts.add(p);
        }
    }
}
