#include "TypstModule.h"
#include <thread>

void installTypst(jsi::Runtime& runtime, std::shared_ptr<react::CallInvoker> callInvoker) {
    // compileTypstAsync(source: string, format: 'pdf' | 'html', fontPath?: string) => Promise<ArrayBuffer>
    auto compileFn = jsi::Function::createFromHostFunction(
        runtime,
        jsi::PropNameID::forAscii(runtime, "compileTypstAsync"),
        3,
        [callInvoker](jsi::Runtime& rt, const jsi::Value& thisValue,
                       const jsi::Value* args, size_t count) -> jsi::Value {

            std::string sourceCode = args[0].asString(rt).utf8(rt);
            std::string format = args[1].asString(rt).utf8(rt);
            std::string fontPath = count > 2 && !args[2].isNull()
                ? args[2].asString(rt).utf8(rt)
                : "";

            auto promiseCtor = rt.global().getPropertyAsFunction(rt, "Promise");
            auto executor = jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "executor"), 2,
                [sourceCode, format, fontPath, callInvoker](
                    jsi::Runtime& runtime,
                    const jsi::Value&,
                    const jsi::Value* promiseArgs, size_t) -> jsi::Value {

                    auto resolve = std::make_shared<jsi::Function>(
                        promiseArgs[0].getObject(runtime).asFunction(runtime));
                    auto reject = std::make_shared<jsi::Function>(
                        promiseArgs[1].getObject(runtime).asFunction(runtime));

                    std::thread([sourceCode, format, fontPath, callInvoker, resolve, reject]() {
                        CompileResult result;
                        if (format == "html") {
                            result = compile_to_html(sourceCode.c_str(), fontPath.c_str());
                        } else {
                            result = compile_to_pdf(sourceCode.c_str(), fontPath.c_str());
                        }

                        callInvoker->invokeAsync([result, resolve, format]() {
                            if (result.data == nullptr) {
                                // TODO: Handle error
                                return;
                            }

                            // Создаем ArrayBuffer
                            jsi::Runtime* runtimePtr = nullptr; // Will be set by caller
                            if (runtimePtr == nullptr) {
                                free_compile_result(result);
                                return;
                            }

                            auto arrayBufferCtor = runtimePtr->global().getPropertyAsFunction(
                                *runtimePtr, "ArrayBuffer");
                            auto arrayBuffer = arrayBufferCtor.callAsConstructor(
                                *runtimePtr, static_cast<int>(result.len))
                                .getObject(*runtimePtr);
                            auto buffer = arrayBuffer->getArrayBuffer(*runtimePtr);

                            memcpy(buffer.data(*runtimePtr), result.data, result.len);
                            free_compile_result(result);

                            // Возвращаем {data: ArrayBuffer, format: string}
                            auto resultObj = jsi::Object(*runtimePtr);
                            resultObj.setProperty(*runtimePtr, "data", std::move(*arrayBuffer));
                            resultObj.setProperty(*runtimePtr, "format",
                                jsi::String::createFromUtf8(*runtimePtr, format));

                            resolve->call(*runtimePtr, std::move(resultObj));
                        });
                    }).detach();

                    return jsi::Value::undefined();
                });

            return promiseCtor.callAsConstructor(rt, std::move(executor));
        });

    runtime.global().setProperty(runtime, "compileTypstAsync", std::move(compileFn));
}
