package com.tabslify.tabs.aitab

data class Model(
    val realname: String,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val weight: Int = 1,
    val name: String = realname.substringAfter("/", realname)
)

val geminiModels = listOf(
    Model("gemini-3.8-flash", vision = true, audio = true, weight = 3),
    Model("gemini-3.7-flash", vision = true, audio = true, weight = 3),
    Model("gemini-3.6-flash", vision = true, audio = true, weight = 2),
    Model("gemini-3.1-flash-lite", vision = true, audio = true, weight = 2),
    Model("gemini-3-flash-preview", vision = true, audio = true, weight = 1),
    Model("gemini-3.5-flash-lite", vision = true, audio = true, weight = 1)
)

val nvidiaModels = listOf(
    Model("openai/gpt-oss-20b", weight = 1),
    Model("z-ai/glm-5-3", weight = 5),
    Model("meta/llama-3.2-11b-vision-instruct", vision = true, weight = 2),
    Model("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", vision = true, weight = 2)
)

val openrouterModels = listOf(
    Model("nvidia/nemotron-3.5-lightning:free", weight = 1, name = "Nemotron 3.5 Lightning (schnell)"),
    Model("google/gemma-4-26b-a4b-it:free", vision = true, weight = 1, name = "Gemma 4 26B A4B"),
    Model("google/gemma-4-31b-it:free", vision = true, weight = 1, name = "Gemma 4 31B"),
    Model("thinkingmachines/inkling-small:free", vision = true, audio = true, weight = 1, name = "Inkling Small")
)

val commandcodeModels = listOf(
    Model("deepseek/deepseek-v4.1-flash", vision = true, weight = 1, name = "DeepSeek V4.1 Flash"),
    Model("deepseek/deepseek-v4.1-flash-fast", vision = true, weight = 1, name = "DeepSeek V4.1 Flash Fast"),
    Model("deepseek/deepseek-v4.1-flash-ultrafast", weight = 1, name = "DeepSeek V4.1 Flash Ultrafast"),
    Model("deepseek/deepseek-v4-flash-vision-exp", vision = true, weight = 1, name = "DeepSeek V4 Flash Vision"),
    Model("deepseek/deepseek-v4-pro", weight = 1, name = "DeepSeek V4 Pro"),
    Model("xiaomi/mimo-v2.6-flash", weight = 1, name = "MiMo V2.6 Flash")
)

val serverModels = listOf(
    Model("qwen2.5:7b"),
    Model("qwen2.5-coder:3b"),
    Model("qwen2.5-coder:7b"),
    Model("qwen2.5-coder:14b"),
    Model("qwen3-coder-next:cloud"),
    Model("qwen3-vl:235b-cloud", true),
    Model("llava:13b", true),
    Model("llama3.2-vision:11b", true),
)