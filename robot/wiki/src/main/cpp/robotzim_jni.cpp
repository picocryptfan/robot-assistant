// SPDX-License-Identifier: GPL-3.0-only
// The JNI of librobotzim.so (:wiki process only; wiki/src/main/java/app/heyrobot/wiki/LibZim.kt): open a ZIM file from a
// descriptor, title suggestions, full-text search, one article's HTML, a metadata value, close. Strings cross as UTF-8
// bytes; every libzim exception stops here and comes back as null (or 0). Nothing here logs, writes or opens a file.
#include <jni.h>
#include <unistd.h>
#include <zim/archive.h>
#include <zim/item.h>
#include <zim/search.h>
#include <zim/suggestion.h>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace {

struct Handle {
    // Open while the archive lives: libzim names its parts "/dev/fd/<fd>" and dups that name (tools/libzim_fd.sh), for
    // the full-text index too, so this descriptor must still be there when a search opens it.
    int fd;
    zim::Archive archive;
    explicit Handle(int own) : fd(own), archive(own) {}
    ~Handle() { close(fd); }
};

Handle* of(jlong h) { return reinterpret_cast<Handle*>(h); }

bool bytes(JNIEnv* env, jbyteArray a, std::string& out, size_t max) {
    if (!a) return false;
    jsize n = env->GetArrayLength(a);
    if (n < 0 || static_cast<size_t>(n) > max) return false;
    out.resize(static_cast<size_t>(n));
    if (n) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return !env->ExceptionCheck();
}

jbyteArray array(JNIEnv* env, const char* p, size_t n) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(n));
    if (a && n) env->SetByteArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(p));
    return a;
}

jobjectArray strings(JNIEnv* env, const std::vector<std::string>& v) {
    jclass cls = env->FindClass("[B");
    if (!cls) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(v.size()), cls, nullptr);
    for (size_t i = 0; out && i < v.size(); i++) {
        jbyteArray b = array(env, v[i].data(), v[i].size());
        if (!b) return nullptr;
        env->SetObjectArrayElement(out, static_cast<jsize>(i), b);
        env->DeleteLocalRef(b);
    }
    return out;
}

constexpr size_t MAX_QUERY = 1024;
constexpr size_t MAX_PATH = 2048;
constexpr jint MAX_RESULTS = 20;

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_heyrobot_wiki_LibZim_nativeOpen(JNIEnv* env, jclass, jint fd, jintArray err) {
    jint e = 1;
    jlong h = 0;
    int own = fd >= 0 ? dup(fd) : -1;  // the Handle's own (closed with it); the caller keeps its descriptor
    if (own >= 0) {
        try {
            h = reinterpret_cast<jlong>(new Handle(own));
            e = 0;
        } catch (...) {
            e = 2;  // not a ZIM file, or a damaged one
            close(own);
        }
    }
    if (err && env->GetArrayLength(err) > 0) env->SetIntArrayRegion(err, 0, 1, &e);
    return h;
}

JNIEXPORT void JNICALL Java_app_heyrobot_wiki_LibZim_nativeClose(JNIEnv*, jclass, jlong h) {
    delete of(h);
}

/** Article count, then 1/0: a title index, a full-text index. */
JNIEXPORT jlongArray JNICALL Java_app_heyrobot_wiki_LibZim_nativeInfo(JNIEnv* env, jclass, jlong h) {
    if (!h) return nullptr;
    try {
        auto& a = of(h)->archive;
        jlong v[3] = {static_cast<jlong>(a.getArticleCount()), a.hasTitleIndex() ? 1 : 0, a.hasFulltextIndex() ? 1 : 0};
        jlongArray out = env->NewLongArray(3);
        if (out) env->SetLongArrayRegion(out, 0, 3, v);
        return out;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL Java_app_heyrobot_wiki_LibZim_nativeMeta(JNIEnv* env, jclass, jlong h, jbyteArray name) {
    std::string n;
    if (!h || !bytes(env, name, n, 64)) return nullptr;
    try {
        std::string v = of(h)->archive.getMetadata(n);
        if (v.size() > 4096) v.resize(4096);
        return array(env, v.data(), v.size());
    } catch (...) {
        return nullptr;
    }
}

/** Title suggestions for [query]: path, title, path, title... at most [max] pairs. */
JNIEXPORT jobjectArray JNICALL Java_app_heyrobot_wiki_LibZim_nativeTitles(JNIEnv* env, jclass, jlong h, jbyteArray query, jint max) {
    std::string q;
    if (!h || max < 1 || max > MAX_RESULTS || !bytes(env, query, q, MAX_QUERY)) return nullptr;
    try {
        zim::SuggestionSearcher searcher(of(h)->archive);
        auto results = searcher.suggest(q).getResults(0, max);
        std::vector<std::string> out;
        for (auto it = results.begin(); it != results.end(); ++it) {
            out.push_back(it->getPath());
            out.push_back(it->getTitle());
        }
        return strings(env, out);
    } catch (...) {
        return nullptr;
    }
}

/** Full-text search for [query]: path, title, path, title... at most [max] pairs, best first. */
JNIEXPORT jobjectArray JNICALL Java_app_heyrobot_wiki_LibZim_nativeSearch(JNIEnv* env, jclass, jlong h, jbyteArray query, jint max) {
    std::string q;
    if (!h || max < 1 || max > MAX_RESULTS || !bytes(env, query, q, MAX_QUERY)) return nullptr;
    try {
        zim::Searcher searcher(of(h)->archive);
        auto results = searcher.search(zim::Query(q)).getResults(0, max);
        std::vector<std::string> out;
        for (auto it = results.begin(); it != results.end(); ++it) {
            out.push_back(it.getPath());
            out.push_back(it.getTitle());
        }
        return strings(env, out);
    } catch (...) {
        return nullptr;
    }
}

/** The article at [path], redirects followed: its path, title and HTML (at most [maxBytes]); null when it isn't HTML. */
JNIEXPORT jobjectArray JNICALL Java_app_heyrobot_wiki_LibZim_nativeArticle(JNIEnv* env, jclass, jlong h, jbyteArray path, jint maxBytes) {
    std::string p;
    if (!h || maxBytes < 1 || !bytes(env, path, p, MAX_PATH)) return nullptr;
    try {
        auto item = of(h)->archive.getEntryByPath(p).getItem(true);
        if (item.getMimetype().rfind("text/html", 0) != 0) return nullptr;
        auto size = static_cast<size_t>(item.getSize());
        auto blob = item.getData(0, std::min(size, static_cast<size_t>(maxBytes)));
        std::vector<std::string> out{item.getPath(), item.getTitle(), std::string(blob.data(), blob.size())};
        return strings(env, out);
    } catch (...) {
        return nullptr;
    }
}

}  // extern "C"
