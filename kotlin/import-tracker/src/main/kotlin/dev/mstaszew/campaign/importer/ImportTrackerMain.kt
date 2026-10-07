package dev.mstaszew.campaign.importer

fun main(args: Array<String>) {
    val trackerPath = args.getOrNull(0) ?: error("usage: import-tracker <tracker.json> [events.jsonl]")
    println("import-tracker stub, tracker=$trackerPath")
}
