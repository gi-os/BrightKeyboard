#!/usr/bin/env python3
"""
Build a language pack: tools/gen_pack.py <code> <out.pack>

A pack is a zip holding exactly the files the keyboard already knows how to read, so nothing on the
Kotlin side learns a new format:

    words.bin      LKD1, same as the bundled English list (see gen_dict.py)
    charmodel.bin  27x27x27 float32 table, same as gen_charmodel.py
    display.txt    folded<TAB>as-written, only for words where the two differ
    meta.txt       key=value: code, name, layout, words, built

Everything in words.bin is FOLDED to a-z. The engine underneath is a-z and only a-z — the trie
branches twenty-six ways, the character model is cubic in the alphabet, and the swipe model emits
twenty-seven classes — and none of that widens cheaply. It does not need to: a phone has no é key,
and folding costs between 0.6% (Italian) and 4.5% (French) of words a shared key, which the
alternatives list already sorts out by frequency. display.txt is how "cafe" comes back as "café".

Two sources, the same split gen_dict.py uses and for the same reason:

  * WHICH words exist: hunspell, the real spelling dictionary for the language. A corpus alone
    carries its own misspellings, and a typo in the dictionary can never be corrected.
  * HOW COMMON each one is: wordfreq, which aggregates several corpora per language.

Neither needs root. Fetch the dictionaries with:

    apt-get download hunspell-es hunspell-fr-classical hunspell-de-de \
                     hunspell-pt-pt hunspell-it hunspell-no
    for f in *.deb; do dpkg-deb -x "$f" ex; done     # .dic/.aff land in ex/usr/share/hunspell
    pip install --user wordfreq spylls
"""
import math
import os
import re
import struct
import subprocess
import sys
import time
import unicodedata
import zipfile

MAGIC = 0x314C4B44  # 'LKD1'
MAX_WORDS = 70_000
# How deep into the frequency list to look before spell-checking. Overridable because spylls is a
# pure-Python hunspell and some affix tables are slow: Icelandic runs at 2.5 ms a word, so the full
# depth would take five minutes. Its list is shallower than that anyway.
TOP_N = int(os.environ.get("PACK_TOP_N", "120000"))
HUNSPELL = os.environ.get("HUNSPELL_DIR", "/tmp/deb/ex/usr/share/hunspell")

# code -> (wordfreq language, hunspell base name, display name, base layout)
LANGS = {
    "es": ("es", "es_ES", "Español", "qwerty"),
    "fr": ("fr", "fr_FR", "Français", "azerty"),
    "de": ("de", "de_DE", "Deutsch", "qwertz"),
    "pt": ("pt", "pt_PT", "Português", "qwerty"),
    "it": ("it", "it_IT", "Italiano", "qwerty"),
    "no": ("nb", "nb_NO", "Norsk", "qwerty"),
    # Neither of these is published as an AOSP word list, so a built pack is the only way to get them.
    "id": ("id", "id_ID", "Bahasa Indonesia", "qwerty"),
    "is": ("is", "is_IS", "Íslenska", "qwerty"),
}

# Letters Unicode will not decompose, because they are not a base letter wearing a mark.
# Must stay in step with Folding.kt's SINGLES — FoldingTest is the check on the Kotlin side.
SINGLES = {"ø": "o", "æ": "ae", "œ": "oe", "ß": "ss",
           "đ": "d", "ð": "d", "ł": "l", "þ": "th", "ħ": "h", "ŋ": "n", "ı": "i"}


def fold(word):
    """Match key for a word: lower case, marks stripped, the letters above spelled out."""
    s = "".join(SINGLES.get(c, c) for c in word.lower())
    s = unicodedata.normalize("NFD", s)
    return "".join(c for c in s if (("a" <= c <= "z") or c == "'") and not unicodedata.combining(c))


# The English dictionary's log-frequency range, in milli-nats. A pack built from a source with no real
# probabilities is mapped onto this, so the decoder's fitted constants keep meaning what they meant.
LOGF_MIN, LOGF_MAX = -17621, -3206


def read_combined(path):
    """
    An AOSP `.combined` word list: a header line, then ` word=<w>, f=<0-255>` lines.

    This is the format of the word lists at codeberg.org/Helium314/aosp-dictionaries, which covers a
    hundred-odd languages and is where every keyboard in this family gets its dictionaries. `f` is
    AOSP's 0-255 frequency scale, not a probability, so it is mapped linearly onto the log-frequency
    range the bundled English list occupies rather than pretending to be one.

    **Check the licence before shipping a pack built this way.** The lists come from many sources and
    the repository names one per language: some are CC BY 4.0 (attribute), some GPLv2 or LGPL-3.0
    (incompatible with shipping inside an MIT release without care). The builder cannot know which,
    so it will not guess.
    """
    out = {}
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            m = re.match(r"\s*word=([^,]+),\s*f=(-?\d+)", line)
            if not m:
                continue
            word, freq = m.group(1), int(m.group(2))
            if freq <= 0 or any(ch.isdigit() for ch in word):
                continue
            key = fold(word)
            if not key or len(key) > 24:
                continue
            prev = out.get(key)
            if prev is None or freq > prev[0]:
                out[key] = (freq, word)
    return out


def write_pack(code, name, layout, ranked, out_path):
    """[ranked] is folded -> (weight, as-written), any positive weight scale."""
    top = sorted(ranked.items(), key=lambda kv: -kv[1][0])[:MAX_WORDS]
    total = sum(w for _, (w, _) in top)
    entries = {k: max(-32768, min(32767, round(math.log(w / total) * 1000))) for k, (w, _) in top}
    ordered = sorted(entries.items(), key=lambda kv: (len(kv[0]), kv[0]))
    words_bin = bytearray(struct.pack("<II", MAGIC, len(ordered)))
    for word, logf in ordered:
        words_bin += struct.pack("<Bh", len(word), logf) + word.encode("ascii")
    display_lines = [f"{k}\t{d}" for k, (_, d) in top if d.lower() != k]

    counts = "\n".join(f"{k} {max(1, int(w * 1e9))}" for k, (w, _) in top)
    tmp_counts, tmp_model = f"/tmp/pack_{code}_counts.txt", f"/tmp/pack_{code}_charmodel.bin"
    with open(tmp_counts, "w", encoding="utf-8") as f:
        f.write(counts)
    here = os.path.dirname(os.path.abspath(__file__))
    subprocess.run([sys.executable, os.path.join(here, "gen_charmodel.py"), tmp_counts, tmp_model],
                   check=True, stdout=subprocess.DEVNULL)

    meta = "\n".join([f"code={code}", f"name={name}", f"layout={layout}",
                      f"words={len(ordered)}", f"built={time.strftime('%Y-%m-%d')}"])
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("meta.txt", meta)
        z.writestr("words.bin", bytes(words_bin))
        z.write(tmp_model, "charmodel.bin")
        z.writestr("display.txt", "\n".join(display_lines))
    print(f"{code}: {len(ordered):,} words, {len(display_lines):,} with accents, "
          f"{os.path.getsize(out_path)/1e6:.2f} MB -> {out_path}")


def build_from_combined(code, name, layout, combined, out_path):
    raw = read_combined(combined)
    if not raw:
        sys.exit(f"{code}: no usable words in {combined} — is it a Latin-script language?")
    # f 0-255 onto the English log range, then back to a weight the shared writer can normalise.
    ranked = {k: (math.exp(LOGF_MIN / 1000 + (f / 255.0) * (LOGF_MAX - LOGF_MIN) / 1000), d)
              for k, (f, d) in raw.items()}
    write_pack(code, name, layout, ranked, out_path)


def spellcheck_slice(code, start, end, cache_path):
    """
    Spell-check one slice of the frequency list and append what passes to [cache_path].

    Exists because spylls is a pure-Python hunspell and some affix tables are slow enough that a full
    pass does not fit in one sitting: Icelandic runs at about 2.5 ms a word. Resumable, so the work
    can be done in chunks and the pack built from the cache afterwards.
    """
    import wordfreq
    from spylls.hunspell import Dictionary
    lang, dic, _, _ = LANGS[code]
    spell = Dictionary.from_files(os.path.join(HUNSPELL, dic))
    words = wordfreq.top_n_list(lang, end)[start:end]
    kept = 0
    with open(cache_path, "a", encoding="utf-8") as f:
        for word in words:
            if any(ch.isdigit() for ch in word):
                continue
            display = word
            if not spell.lookup(word):
                cap = word.capitalize()
                if not spell.lookup(cap):
                    continue
                display = cap
            w = wordfreq.word_frequency(word, lang)
            if w <= 0:
                continue
            f.write(f"{display}\t{w!r}\n")
            kept += 1
    print(f"{code}: slice {start}-{end} kept {kept:,} -> {cache_path}")


def build_from_cache(code, cache_path, out_path):
    lang, dic, name, layout = LANGS[code]
    ranked = {}
    with open(cache_path, encoding="utf-8") as f:
        for line in f:
            display, _, w = line.rstrip("\n").partition("\t")
            try:
                weight = float(w)
            except ValueError:
                continue
            key = fold(display)
            if not key or len(key) > 24:
                continue
            prev = ranked.get(key)
            if prev is None or weight > prev[0]:
                ranked[key] = (weight, display)
    if not ranked:
        sys.exit(f"{code}: cache {cache_path} held nothing usable")
    write_pack(code, name, layout, ranked, out_path)


def build(code, out_path):
    import wordfreq
    from spylls.hunspell import Dictionary

    lang, dic, name, layout = LANGS[code]
    spell = Dictionary.from_files(os.path.join(HUNSPELL, dic))

    # Frequency order, spell-checked. Capitalised forms are checked too: a hunspell dictionary holds
    # proper nouns with their capital, so "españa" fails where "España" passes, and dropping those
    # would lose the country you live in from your keyboard.
    best = {}          # folded -> (weight, as-written)
    checked = 0
    for word in wordfreq.top_n_list(lang, TOP_N):
        if any(ch.isdigit() for ch in word):
            continue                                    # "2e", "m3" — corpus grit, not words
        display = word
        if not spell.lookup(word):
            cap = word.capitalize()
            if not spell.lookup(cap):
                continue
            display = cap
        checked += 1
        key = fold(word)
        if not key or len(key) > 24:
            continue
        weight = wordfreq.word_frequency(word, lang)
        if weight <= 0:
            continue
        # First writing of a key wins: the list is in frequency order, so that is the common spelling.
        if key not in best:
            best[key] = (weight, display)

    if not best:
        sys.exit(f"{code}: nothing survived the spell check")

    top = sorted(best.items(), key=lambda kv: -kv[1][0])[:MAX_WORDS]
    total = sum(w for _, (w, _) in top)

    entries = {k: max(-32768, min(32767, round(math.log(w / total) * 1000))) for k, (w, _) in top}
    ordered = sorted(entries.items(), key=lambda kv: (len(kv[0]), kv[0]))
    words_bin = bytearray(struct.pack("<II", MAGIC, len(ordered)))
    for word, logf in ordered:
        words_bin += struct.pack("<Bh", len(word), logf) + word.encode("ascii")

    # Only the words whose written form differs from their key need a line here.
    display_lines = [f"{k}\t{d}" for k, (_, d) in top if d.lower() != k]

    # The character model wants "word count" lines, which is what it has always wanted. Feeding it
    # the folded words keeps it in the same alphabet as everything that consults it.
    counts = "\n".join(f"{k} {int(w * 1e9)}" for k, (w, _) in top if int(w * 1e9) > 0)
    tmp_counts = f"/tmp/pack_{code}_counts.txt"
    tmp_model = f"/tmp/pack_{code}_charmodel.bin"
    with open(tmp_counts, "w", encoding="utf-8") as f:
        f.write(counts)
    here = os.path.dirname(os.path.abspath(__file__))
    subprocess.run([sys.executable, os.path.join(here, "gen_charmodel.py"), tmp_counts, tmp_model],
                   check=True, stdout=subprocess.DEVNULL)

    meta = "\n".join([
        f"code={code}", f"name={name}", f"layout={layout}",
        f"words={len(ordered)}", f"built={time.strftime('%Y-%m-%d')}",
    ])

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("meta.txt", meta)
        z.writestr("words.bin", bytes(words_bin))
        z.write(tmp_model, "charmodel.bin")
        z.writestr("display.txt", "\n".join(display_lines))

    size = os.path.getsize(out_path)
    print(f"{code}: {checked:,} spell-checked, {len(ordered):,} words, "
          f"{len(display_lines):,} with accents, {size/1e6:.2f} MB -> {out_path}")


if __name__ == "__main__":
    # Two sources. wordfreq + hunspell for the six built here; an AOSP .combined word list for
    # anything else, which is how the hundred-odd languages at aosp-dictionaries become available.
    #
    #   gen_pack.py es                                   es.pack
    #   gen_pack.py --combined ca Català qwerty main_ca.combined ca.pack
    if len(sys.argv) > 1 and sys.argv[1] == "--slice":
        # gen_pack.py --slice <code> <start> <end> <cache>
        spellcheck_slice(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5])
    elif len(sys.argv) > 1 and sys.argv[1] == "--from-cache":
        # gen_pack.py --from-cache <code> <cache> [out.pack]
        build_from_cache(sys.argv[2], sys.argv[3],
                         sys.argv[4] if len(sys.argv) > 4 else f"{sys.argv[2]}.pack")
    elif len(sys.argv) > 1 and sys.argv[1] == "--combined":
        if len(sys.argv) < 6:
            sys.exit("usage: gen_pack.py --combined <code> <name> <layout> <in.combined> [out.pack]")
        _, _, code, name, layout, combined = sys.argv[:6]
        out = sys.argv[6] if len(sys.argv) > 6 else f"{code}.pack"
        build_from_combined(code, name, layout, combined, out)
    elif len(sys.argv) > 1 and sys.argv[1] in LANGS:
        c = sys.argv[1]
        build(c, sys.argv[2] if len(sys.argv) > 2 else f"{c}.pack")
    else:
        sys.exit(f"usage: gen_pack.py <{'|'.join(LANGS)}> [out.pack]\n"
                 f"       gen_pack.py --combined <code> <name> <layout> <in.combined> [out.pack]")
