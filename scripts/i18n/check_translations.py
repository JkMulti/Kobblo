"""Checks every translation against the English strings.

    python3 scripts/i18n/check_translations.py

For each values-xx folder it reports strings the English has that the language lacks, strings the language has
that the English no longer does, placeholders that differ from the English (a missing %1$s shows as a literal or
crashes String.format), plurals without the forms the language needs, and XML escape codes left in the text.
It exits 1 when anything is wrong, so it can gate a build.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
# The plural forms Android (CLDR) uses in each language the app ships.
PLURALS = {"ar": {"zero", "one", "two", "few", "many", "other"}, "ru": {"one", "few", "many", "other"},
           "pl": {"one", "few", "many", "other"}, "fr": {"one", "many", "other"}, "es": {"one", "many", "other"},
           "it": {"one", "many", "other"}, "pt": {"one", "many", "other"}, "de": {"one", "other"},
           "hi": {"one", "other"}, "tr": {"one", "other"}, "ur": {"one", "other"}, "ja": {"other"},
           "zh": {"other"}, "ko": {"other"}, "in": {"other"}}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]")


def placeholders(text):
    return sorted(PLACEHOLDER.findall((text or "").replace("%%", "")))


def load(folder):
    out = {}
    for path in sorted(glob.glob(os.path.join(RES, folder, "strings*.xml"))):
        for el in ET.parse(path).getroot():
            if el.tag == "string":
                out[el.get("name")] = ("string", "".join(el.itertext()), el.get("translatable") != "false")
            elif el.tag == "plurals":
                out[el.get("name")] = ("plurals", {i.get("quantity"): "".join(i.itertext()) for i in el}, True)
    return out


def check(lang, english):
    found = load(f"values-{lang}")
    problems = []
    for key, (tag, text, translatable) in english.items():
        if not translatable:
            continue
        if key not in found:
            problems.append(f"missing {key}")
            continue
        ttag, ttext, _ = found[key]
        if tag != ttag:
            problems.append(f"{key}: a {ttag} where English has a {tag}")
            continue
        values = [ttext] if tag == "string" else list(ttext.values())
        if any(re.search(r"&(amp|lt|gt);", v) for v in values):
            problems.append(f"{key}: an XML escape code shows as text")
        if tag == "string":
            if placeholders(ttext) != placeholders(text):
                problems.append(f"{key}: placeholders {placeholders(ttext)}, English has {placeholders(text)}")
        else:
            need = PLURALS.get(lang)
            if need and set(ttext) != need:
                problems.append(f"{key}: plural forms {sorted(ttext)}, {lang} needs {sorted(need)}")
            want = placeholders(text.get("other"))
            for quantity, value in ttext.items():
                got = placeholders(value)
                # A singular form may leave the number out ("one file"), but never adds or changes one.
                if got != want and not (quantity in ("zero", "one", "two") and set(got) <= set(want)):
                    problems.append(f"{key}[{quantity}]: placeholders {got}, English has {want}")
    for key in found:
        if key not in english:
            problems.append(f"{key}: no longer in the English")
    return problems


def main():
    english = load("values")
    langs = sorted(os.path.basename(p)[len("values-"):] for p in glob.glob(os.path.join(RES, "values-*"))
                   if glob.glob(os.path.join(p, "strings*.xml")))
    failed = False
    for lang in langs:
        problems = check(lang, english)
        print(f"{lang}: {'ok' if not problems else f'{len(problems)} problems'}")
        for p in problems[:20]:
            print(f"    {p}")
        failed |= bool(problems)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
