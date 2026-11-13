// SPDX-License-Identifier: GPL-3.0-only
// The build machine's check (tools/build_libzim.sh, host build): ICU's cut data (icu-filter.json) still does what libzim's
// zim::removeAccents asks of it, the same way: the default converter set to UTF-8, the same transliterator. Not shipped.
#include <unicode/translit.h>
#include <unicode/ucnv.h>
#include <unicode/unistr.h>
#include <unicode/locid.h>
#include <cstdio>
#include <memory>
#include <string>

int main() {
    ucnv_setDefaultName("UTF-8");
    UErrorCode status = U_ZERO_ERROR;
    std::unique_ptr<icu::Transliterator> t(icu::Transliterator::createInstance("Lower; NFD; [:M:] remove; NFC", UTRANS_FORWARD, status));
    if (U_FAILURE(status) || !t) { std::printf("ICU check FAILED: %s\n", u_errorName(status)); return 1; }
    const char* cases[][2] = {{"Louis Riel", "louis riel"}, {"\xc3\x89" "cole Polytechnique", "ecole polytechnique"},
                              {"Fran\xc3\xa7ois L\xc3\xa9vesque", "francois levesque"}, {"Montr\xc3\xa9" "al", "montreal"}};
    for (auto& c : cases) {
        icu::UnicodeString u(c[0]);
        t->transliterate(u);
        std::string out;
        u.toUTF8String(out);
        if (out != c[1]) { std::printf("ICU check FAILED: %s -> %s\n", c[0], out.c_str()); return 1; }
    }
    icu::Locale fr("fra"), en("eng");
    if (fr.isBogus() || en.isBogus()) { std::printf("ICU check FAILED: locale\n"); return 1; }
    std::printf("ok  ICU's cut data folds accents as libzim asks\n");
    return 0;
}
