#pragma once

#include <jsi/jsi.h>
#include <ReactCommon/CallInvoker.h>
#include <string>

using namespace facebook;

struct CompileResult {
    uint8_t* data;
    size_t len;
};

enum class CompileFormat { Pdf = 0, Html = 1 };

extern "C" {
    CompileResult compile_to_pdf(const char* source, const char* font_path);
    CompileResult compile_to_html(const char* source, const char* font_path);
    void free_compile_result(CompileResult result);
}

void installTypst(jsi::Runtime& runtime, std::shared_ptr<react::CallInvoker> callInvoker);
