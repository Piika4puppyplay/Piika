package fr.piika.puppyphone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dispositions de PupKeyboard (AZERTY, QWERTY, symboles, pavés numériques) + accents et emojis. */
final class KbLayouts {
    static final int CH = 0, SHIFT = -1, SYM = -2, ABC = -3, ENTER = -4, DEL = -5, EMOJI = -6, SYM2 = -7, SPACE = -8;

    static final class Key {
        String label, out, hint;
        int code;
        float w = 1f;
        String[] alts;
        float x, y, kw, kh; // géométrie en pixels
        Key(String label, String out, int code, float w) { this.label = label; this.out = out; this.code = code; this.w = w; }
        boolean isChar() { return code == CH; }
    }

    static final Map<String, String> ALTS = new HashMap<>();
    static {
        ALTS.put("a", "à â ä æ á ã å ª"); ALTS.put("e", "é è ê ë ę ė ē €"); ALTS.put("i", "î ï í ì į ī"); ALTS.put("o", "ô ö ò ó œ õ ø º");
        ALTS.put("u", "ù û ü ú ū"); ALTS.put("c", "ç ć č ©"); ALTS.put("y", "ÿ ý ¥"); ALTS.put("n", "ñ ń"); ALTS.put("s", "ß ś š $");
        ALTS.put("z", "ž ź ż"); ALTS.put("l", "ł"); ALTS.put("g", "ğ"); ALTS.put("d", "ð"); ALTS.put("r", "®"); ALTS.put("t", "™ þ");
        ALTS.put("'", "’ \" « » ` ´"); ALTS.put(".", "… , ? ! ; : - @ # & /"); ALTS.put(",", "; : …");
        ALTS.put("1", "¹ ½ ⅓ ¼"); ALTS.put("2", "² ⅔"); ALTS.put("3", "³ ¾"); ALTS.put("0", "° ∅"); ALTS.put("-", "_ – — ·"); ALTS.put("€", "$ £ ¥ ¢ ₿");
        ALTS.put("!", "¡"); ALTS.put("?", "¿"); ALTS.put("(", "[ { <"); ALTS.put(")", "] } >"); ALTS.put("\"", "« » “ ” „"); ALTS.put("*", "★ † ‡ ♥ 🐾");
        ALTS.put("+", "± ×"); ALTS.put("/", "\\ ÷ |"); ALTS.put("=", "≠ ≈ ∞"); ALTS.put("%", "‰"); ALTS.put("@", "©"); ALTS.put("&", "§ ¶");
    }
    static final String[] DIGITS_ROW = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    static Key ch(String c) {
        Key k = new Key(c, c, CH, 1f);
        String a = ALTS.get(c);
        if (a != null) k.alts = a.split(" ");
        return k;
    }
    static Key sp(String label, int code, float w) { return new Key(label, "", code, w); }

    static List<List<Key>> letters(String layout, boolean numberRow, String commaKey) {
        List<List<Key>> rows = new ArrayList<>();
        String[] r;
        boolean az = "azerty".equals(layout);
        if (az) r = new String[]{"a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"};
        else r = new String[]{"q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"};
        if (numberRow) { List<Key> nr = new ArrayList<>(); for (String d : DIGITS_ROW) nr.add(ch(d)); rows.add(nr); }
        for (int i = 0; i < 3; i++) {
            List<Key> row = new ArrayList<>();
            if (i == 2) row.add(sp("⇧", SHIFT, 1.5f));
            String[] cs = r[i].split(" ");
            for (int j = 0; j < cs.length; j++) {
                Key k = ch(cs[j]);
                if (i == 0 && !numberRow && j < 10) {
                    k.hint = DIGITS_ROW[j];
                    String[] base = k.alts == null ? new String[0] : k.alts;
                    String[] na = new String[base.length + 1];
                    na[0] = DIGITS_ROW[j];
                    System.arraycopy(base, 0, na, 1, base.length);
                    k.alts = na;
                }
                row.add(k);
            }
            if (i == 2) row.add(sp("⌫", DEL, 1.5f));
            rows.add(row);
        }
        rows.add(bottom("?123", SYM, commaKey));
        return rows;
    }

    static List<Key> bottom(String modeLabel, int modeCode, String commaKey) {
        List<Key> b = new ArrayList<>();
        b.add(sp(modeLabel, modeCode, 1.5f));
        b.add(ch(commaKey));
        b.add(sp("😊", EMOJI, 1f));
        b.add(sp(" ", SPACE, 3.9f));
        b.add(ch("."));
        b.add(sp("⏎", ENTER, 1.6f));
        return b;
    }

    static List<List<Key>> symbols(int page, String commaKey) {
        List<List<Key>> rows = new ArrayList<>();
        String[] r = page == 1
                ? new String[]{"1 2 3 4 5 6 7 8 9 0", "@ # € _ & - + ( ) /", "* \" ' : ; ! ?"}
                : new String[]{"~ ` | • √ π ÷ × ¶ ∆", "£ $ ¥ ^ ° = { } \\ %", "© ® ™ ✓ [ ] < >"};
        for (int i = 0; i < 3; i++) {
            List<Key> row = new ArrayList<>();
            if (i == 2) row.add(page == 1 ? sp("=\\<", SYM2, 1.5f) : sp("?123", SYM, 1.5f));
            for (String c : r[i].split(" ")) row.add(ch(c));
            if (i == 2) row.add(sp("⌫", DEL, 1.5f));
            rows.add(row);
        }
        rows.add(bottom("ABC", ABC, commaKey));
        return rows;
    }

    static List<List<Key>> numpad(boolean phone) {
        List<List<Key>> rows = new ArrayList<>();
        String[][] r = phone
                ? new String[][]{{"1", "2", "3", "-"}, {"4", "5", "6", "+"}, {"7", "8", "9", "DEL"}, {"*", "0", "#", "ENTER"}}
                : new String[][]{{"1", "2", "3", "-"}, {"4", "5", "6", "/"}, {"7", "8", "9", "DEL"}, {",", "0", ".", "ENTER"}};
        for (String[] rr : r) {
            List<Key> row = new ArrayList<>();
            for (String c : rr) {
                if ("DEL".equals(c)) row.add(sp("⌫", DEL, 1f));
                else if ("ENTER".equals(c)) row.add(sp("⏎", ENTER, 1f));
                else { Key k = ch(c); k.w = 1f; if ("0".equals(c) && phone) k.alts = new String[]{"+"}; if ("*".equals(c) && phone) k.alts = new String[]{",", ";", "N"}; row.add(k); }
            }
            rows.add(row);
        }
        List<Key> last = new ArrayList<>();
        last.add(sp("ABC", ABC, 1f)); last.add(sp(" ", SPACE, 2f)); last.add(ch(phone ? "(" : "%")); last.add(ch(phone ? ")" : ":"));
        if (!phone) { /* ligne bonus */ }
        rows.add(last);
        return rows;
    }

    // ------------------------------------------------------------------ emojis
    static final String[] EMO_TABS = {"🕘", "🐶", "😀", "❤️", "🍕", "⚽", "✈️", "💡", "🔣"};
    static final String[][] EMO = {
            {},
            ("🐶 🐕 🐩 🦮 🐕‍🦺 🐾 🦴 🐺 🦊 🐱 🐈 🐈‍⬛ 🦁 🐯 🐻 🐼 🐨 🐰 🐹 🐭 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦄 🐴 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐬 🐳 🦈 🐊 🐘 🦒 🦓 🦘 🐿️ 🦔 🌸 🌹 🌻 🌈 ⭐ 🌙 ☀️ ⚡ 🔥 💧 🌊").split(" "),
            ("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 💪 🦾 👀 👅 👄 💋").split(" "),
            ("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ❤️‍🔥 ❤️‍🩹 💌 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤 🏳️‍🌈 🏳️‍⚧️ 🌈 🔒 🔓 ⛓️ 🔗 🎀 🎁 🏆 🥇 👑 💎").split(" "),
            ("🍕 🍔 🍟 🌭 🥪 🌮 🌯 🥙 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🥗 🍿 🧂 🥓 🥩 🍗 🍖 🦴 🥚 🍳 🧇 🥞 🧈 🍞 🥐 🥖 🧀 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥕 🌽 🌶️ 🥔 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍮 🍯 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🧊").split(" "),
            ("⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛸️ 🎿 🛹 🛼 🏋️ 🤸 🚴 🏊 🧗 🎮 🕹️ 🎲 🧩 🎯 🎳 🎸 🎹 🥁 🎷 🎺 🎻 🎤 🎧 🎬 🎨 🎭 🎪 🎟️ 🎫 🎉 🎊 🎈 🪩 🕺 💃").split(" "),
            ("✈️ 🚀 🛸 🚁 🚂 🚆 🚇 🚊 🚌 🚕 🚗 🏎️ 🚓 🚑 🚒 🛵 🏍️ 🚲 🛴 ⛵ 🚤 🛳️ ⚓ 🗺️ 🧭 🏔️ ⛰️ 🌋 🏕️ 🏖️ 🏝️ 🏜️ 🏙️ 🌃 🌆 🌉 🎡 🎢 🏰 🗼 🗽 🏠 🏡 🏨 🏪 ⛪ 🕌 ⛩️ 🌍 🌎 🌏").split(" "),
            ("💡 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 📷 📸 🎥 📺 📻 ⏰ ⌚ 🔋 🔌 💸 💶 💳 🧾 ✉️ 📦 📝 📌 📎 ✂️ 🔑 🗝️ 🔨 🛠️ 🔧 🧲 💊 💉 🩹 🧸 🪀 🛍️ 🛒 🎒 👓 🕶️ 👟 👠 👢 🧢 👒 🎩 👕 👖 🩲 🧦 🧥 👗 💄 💍 🔮 🧿 🪄 🕯️ 🛁 🛏️ 🚪").split(" "),
            ("✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ➰ ➿ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 ⬆️ ⬇️ ⬅️ ➡️ ↩️ ↪️ 🔄 🔃 🔀 🔁 🔂 ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ 🔊 🔇 🔔 🔕 📣 ⚠️ 🚫 ⛔ 🔞 ♻️ 🆗 🆕 🆒 🆓 🆙 🔝 ©️ ®️ ™️ #️⃣ 🔟 💲 🇫🇷 🏴‍☠️").split(" "),
    };
}
