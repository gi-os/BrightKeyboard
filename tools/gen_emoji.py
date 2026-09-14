#!/usr/bin/env python3
"""
Build the keyboard's bundled emoji table: app/src/main/res/raw/emoji.bin

The emoji panel used to be 24 glyphs written out by hand in LightKeyboardView. That is fine as a
placeholder and useless as a keyboard — there was no way to reach the other 1,900, and no way to
search for any of them. This generates the whole set, with the names and keywords search needs.

Two sources, doing two different jobs, same division of labour as gen_dict.py:

  * WHICH emoji exist, what they are called, and how they group: Unicode's own `emoji-test.txt`.
    It carries the group/subgroup headings the panel's categories come from, and marks every
    sequence `fully-qualified`, `minimally-qualified`, `unqualified` or `component`. Only
    fully-qualified entries are kept — the others are the same emoji spelled without its variation
    selector, and offering both would put visual duplicates in the grid.
  * WHAT each one is called in other words: CLDR's English annotations. `emoji-test.txt` gives
    "grinning face"; CLDR adds "face | grin | smile", which is what makes searching for "smile"
    work. Searching only the formal name finds almost nothing people actually type.

**Skin tones and gender are collapsed onto a base.** Listed separately they are about 3,800 entries,
most of them the same hand five times, and a grid of near-duplicates is harder to use than a short
one. So `person: light skin tone` folds into `person`, which records that tones exist. The keyboard
applies the user's chosen tone when it draws the panel, and offers every variant when one is tapped.
The same goes for the gendered forms, which Unicode spells as separate sequences rather than as
modifiers — `man farmer` and `woman farmer` fold into `farmer` and come back on the tap.

Format (little-endian throughout). Flat primitive arrays on the Kotlin side, no per-emoji object:

    magic     u32    'LKE1'
    count     u32    number of base emoji
    groups    u8     number of groups
      per group:
        len     u8     group name length in UTF-8 bytes
        bytes   len    group name ("Smileys & Emotion")
    then `count` records, in Unicode's own order (which is the order the panel shows):
        group   u8     index into the group table
        flags   u8     bit 0: has skin-tone variants. bit 1: has gendered or hair variants.
        glyph   u8+n   length-prefixed UTF-8 of the base sequence
        name    u8+n   length-prefixed UTF-8, lowercase ("grinning face")
        words   u8+n   length-prefixed UTF-8, lowercase, space-separated keywords, name excluded
        nvars   u8     number of variant sequences that follow
          per variant:
            glyph u8+n   length-prefixed UTF-8 of the fully-qualified variant

**Variants are stored, not derived.** Deriving them in the keyboard would mean reimplementing two
different Unicode schemes — a modifier appended to the first character for skin tone, a ZWJ and a
gender sign for most roles, an outright different leading character for others — and any mistake
produces a sequence no font has, which draws as a box. The generator has already seen the real
fully-qualified spellings, so it writes them down. About 17 KB for the whole set.

Unicode's order is deliberate and worth keeping: faces before hands before animals, and within a
group the sequences are already arranged the way every emoji picker in the world arranges them.
Sorting it any other way would only make it unfamiliar.

Regenerate:
    python3 gen_emoji.py ../app/src/main/res/main/res/raw/emoji.bin
    # or, offline, with the two sources already downloaded:
    python3 gen_emoji.py ../app/src/main/res/raw/emoji.bin /tmp/emoji-test.txt /tmp/cldr-en.xml

Sources are Unicode Inc. data files, used under the Unicode License (permissive, attribution only).
"""
import os
import re
import struct
import sys
import urllib.request
import xml.etree.ElementTree as ET

MAGIC = 0x31454B4C  # 'LKE1' little-endian

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR_EN_URL = "https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml"

# The five Fitzpatrick modifiers, and the tag Unicode uses for them in a name.
SKIN_MODIFIERS = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]
SKIN_NAME = re.compile(
    r",?\s*(light skin tone|medium-light skin tone|medium skin tone|"
    r"medium-dark skin tone|dark skin tone)"
)

# Gendered spellings. Unicode has two schemes and both have to be folded: an explicit ZWJ with the
# man/woman sign, and names that simply begin with "man "/"woman ".
MAN, WOMAN, PERSON = 0x1F468, 0x1F469, 0x1F9D1
MALE_SIGN, FEMALE_SIGN = 0x2642, 0x2640
ZWJ = 0x200D
VS16 = 0xFE0F

# The four hair components. Like skin tones they are an appearance axis rather than a different
# emoji, and they combine with both the gendered and the neutral person — so "man: red hair",
# "woman: red hair" and "person: red hair" are three spellings of one choice, times four hair types.
# Folding them the way skin tones fold keeps twelve near-duplicates out of the grid.
HAIR = {0x1F9B0, 0x1F9B1, 0x1F9B2, 0x1F9B3}
HAIR_NAME = re.compile(r",?\s*:?\s*(red hair|curly hair|white hair|bald)")

# Entries that are not emoji anyone picks from a grid: the bare skin-tone swatches, the regional
# indicator letters flags are built from, and the hair components on their own.
COMPONENT_ONLY = set(SKIN_MODIFIERS) | set(range(0x1F1E6, 0x1F200)) | HAIR


def fetch(url, cache):
    """Read [cache] if it exists, otherwise download [url] into it. Keeps reruns offline."""
    if os.path.exists(cache):
        return open(cache, encoding="utf-8").read()
    print(f"fetching {url}")
    with urllib.request.urlopen(url, timeout=60) as r:
        text = r.read().decode("utf-8")
    with open(cache, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def parse_emoji_test(text):
    """Every fully-qualified emoji, in file order, as (codepoints, name, group)."""
    out = []
    group = None
    for line in text.splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line or line.startswith("#"):
            continue
        # 1F600 ; fully-qualified # 😀 E1.0 grinning face
        fields, _, comment = line.partition("#")
        parts = fields.split(";")
        if len(parts) != 2:
            continue
        status = parts[1].strip()
        if status != "fully-qualified":
            continue
        cps = tuple(int(c, 16) for c in parts[0].split())
        if len(cps) == 1 and cps[0] in COMPONENT_ONLY:
            continue
        # The comment is "😀 E1.0 grinning face" — drop the glyph and the version.
        name = comment.strip().split(" ", 2)
        name = name[2].strip().lower() if len(name) >= 3 else ""
        if not name:
            continue
        out.append((cps, name, group))
    return out


def parse_cldr(text):
    """CLDR keywords, keyed by the emoji string itself."""
    root = ET.fromstring(text)
    words = {}
    for ann in root.iter("annotation"):
        if ann.get("type") == "tts":
            continue                       # the spoken name, which emoji-test.txt already gives
        cp = ann.get("cp")
        if cp and ann.text:
            words[cp] = [w.strip().lower() for w in ann.text.split("|") if w.strip()]
    return words


def strip_skin(cps, name):
    """
    [cps] and [name] with any skin-tone modifier removed, and whether one was there.

    The name has to be cleaned as well as the codepoints. A two-handed emoji carries a tone per hand,
    so leaving the name alone produced entries reading "handshake: light skin tone, medium-light skin
    tone" in a grid whose whole point is that tones are picked once in settings.
    """
    kept = tuple(c for c in cps if c not in SKIN_MODIFIERS)
    if len(kept) == len(cps):
        return kept, name, False
    clean = SKIN_NAME.sub("", name).strip().rstrip(":,").strip()
    return kept, clean or name, True


def strip_hair(cps, name):
    """The same fold for the four hair components. See [HAIR]."""
    kept = []
    for c in cps:
        if c in HAIR:
            continue
        kept.append(c)
    if len(kept) == len(cps):
        return tuple(kept), name, False
    while kept and kept[-1] in (ZWJ, VS16):
        kept.pop()
    clean = HAIR_NAME.sub("", name).strip().rstrip(":,").strip()
    return tuple(kept), clean or name, True


def degender(cps, name):
    """
    Fold a gendered sequence onto its neutral base.

    Returns (base codepoints, base name, was_gendered). Unicode spells this two ways and both show
    up in the grid as near-duplicates, so both fold:

      * an explicit sign — "man" ZWJ "farmer" or a sequence ending ZWJ male-sign — and
      * a name that simply starts with "man " or "woman ", where the codepoints differ outright.
    """
    # Trailing ZWJ + male/female sign: "police officer" is the base, with two gendered spellings.
    if len(cps) >= 3 and cps[-1] in (MALE_SIGN, VS16):
        trimmed = [c for c in cps if c not in (MALE_SIGN, FEMALE_SIGN)]
        if len(trimmed) != len(cps):
            while trimmed and trimmed[-1] in (ZWJ, VS16):
                trimmed.pop()
            base_name = re.sub(r"^(man|woman)\s+", "", name)
            return tuple(trimmed), base_name, True
    if len(cps) >= 2 and (MALE_SIGN in cps or FEMALE_SIGN in cps):
        trimmed = [c for c in cps if c not in (MALE_SIGN, FEMALE_SIGN)]
        while trimmed and trimmed[-1] in (ZWJ, VS16):
            trimmed.pop()
        return tuple(trimmed), re.sub(r"^(man|woman)\s+", "", name), True
    # "man farmer" / "woman farmer" -> the person form, which Unicode also defines.
    if cps and cps[0] in (MAN, WOMAN) and len(cps) > 1:
        m = re.match(r"^(man|woman)\s+(.*)$", name)
        if m:
            return (PERSON,) + cps[1:], m.group(2), True
    return cps, name, False


def cldr_words(cldr, glyph):
    """
    CLDR's keywords for [glyph], trying the spellings CLDR actually uses as keys.

    CLDR keys its annotations on the **unqualified** sequence — `☺` where emoji-test.txt says `☺️` —
    so a direct lookup misses every emoji that carries a variation selector, which is a third of the
    set. Trying the stripped form as well takes the miss rate from about 34% to about 15%, and what
    is left is mostly flags, whose annotations live in a different CLDR file and whose names already
    contain the country.
    """
    for key in (glyph, glyph.replace(chr(VS16), "")):
        if key in cldr:
            return cldr[key]
    return []


def build(emoji_test, cldr):
    """Fold the whole set onto its bases, keeping Unicode's order."""
    # Canonical spelling for every emoji, keyed without its variation selector.
    #
    # Needed because stripping a modifier can leave a sequence that is *not* how Unicode spells the
    # base. A skin-tone form carries no VS16 — the modifier stands in for it — so removing the tone
    # from 1F590 1F3FB gives a bare 1F590, while the base row in emoji-test.txt is 1F590 FE0F. The
    # two then fail to merge and the grid shows the same open hand twice, which it was measured
    # doing. Routing every stripped result back through this makes the fold exact.
    canonical = {}
    for cps, _, _ in emoji_test:
        canonical.setdefault(tuple(c for c in cps if c != VS16), cps)

    def normalize(cps):
        return canonical.get(tuple(c for c in cps if c != VS16), cps)

    bases = {}      # base codepoints -> record
    order = []
    for cps, name, group in emoji_test:
        no_skin, name, had_skin = strip_skin(cps, name)
        no_hair, name, had_hair = strip_hair(no_skin, name)
        base_cps, base_name, had_gender = degender(no_hair, name)
        base_cps = normalize(base_cps)
        if not base_cps:
            continue
        rec = bases.get(base_cps)
        if rec is None:
            rec = {
                "cps": base_cps,
                "name": base_name,
                "group": group,
                "skin": False,
                "gender": False,
                "hair": False,
                "variants": [],
            }
            bases[base_cps] = rec
            order.append(base_cps)
        rec["skin"] = rec["skin"] or had_skin
        rec["gender"] = rec["gender"] or had_gender
        rec["hair"] = rec["hair"] or had_hair
        if (had_skin or had_gender or had_hair) and cps not in rec["variants"]:
            rec["variants"].append(cps)
        # A base met first as a variant keeps the shorter, neutral name when its own row arrives.
        if base_cps == normalize(cps):
            rec["name"] = name
            rec["group"] = group

    records = []
    for cps in order:
        rec = bases[cps]
        glyph = "".join(chr(c) for c in cps)
        # Keywords: CLDR's, plus the words of the name itself, minus duplicates. The name is stored
        # separately and searched separately, so it is not repeated here.
        name_words = set(re.findall(r"[a-z0-9']+", rec["name"]))
        kw = []
        for w in cldr_words(cldr, glyph):
            for part in re.findall(r"[a-z0-9']+", w):
                if part not in name_words and part not in kw:
                    kw.append(part)
        variants = ["".join(chr(c) for c in v) for v in rec["variants"]]
        if len(variants) > 255:
            variants = variants[:255]
        records.append((rec["group"], rec["skin"], rec["gender"] or rec["hair"], glyph,
                        rec["name"], " ".join(kw), variants))
    return records


def write(path, records):
    groups = []
    for group, *_ in records:
        if group not in groups:
            groups.append(group)
    if len(groups) > 255:
        raise SystemExit("more groups than the format allows")

    def pstr(s):
        b = s.encode("utf-8")
        if len(b) > 255:
            b = b[:255]
        return struct.pack("<B", len(b)) + b

    with open(path, "wb") as f:
        f.write(struct.pack("<IIB", MAGIC, len(records), len(groups)))
        for g in groups:
            f.write(pstr(g))
        for group, skin, gender, glyph, name, words, variants in records:
            flags = (1 if skin else 0) | (2 if gender else 0)
            f.write(struct.pack("<BB", groups.index(group), flags))
            f.write(pstr(glyph))
            f.write(pstr(name))
            f.write(pstr(words))
            f.write(struct.pack("<B", len(variants)))
            for v in variants:
                f.write(pstr(v))
    return groups


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out = sys.argv[1]
    test_src = sys.argv[2] if len(sys.argv) > 2 else "/tmp/emoji-test.txt"
    cldr_src = sys.argv[3] if len(sys.argv) > 3 else "/tmp/cldr-en.xml"

    records = build(
        parse_emoji_test(fetch(EMOJI_TEST_URL, test_src)),
        parse_cldr(fetch(CLDR_EN_URL, cldr_src)),
    )
    groups = write(out, records)

    skin = sum(1 for r in records if r[1])
    gender = sum(1 for r in records if r[2])
    no_words = sum(1 for r in records if not r[5])
    nvar = sum(len(r[6]) for r in records)
    print(f"{len(records)} base emoji across {len(groups)} groups -> {out}")
    print(f"  {os.path.getsize(out) / 1024:.0f} KB")
    print(f"  {skin} with skin tones, {gender} with gendered forms, {nvar} variant sequences")
    print(f"  {no_words} with no CLDR keywords")
    for g in groups:
        print(f"    {sum(1 for r in records if r[0] == g):5d}  {g}")


if __name__ == "__main__":
    main()
