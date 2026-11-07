package com.imperialreader.data.source

class ManhwaHubSource : MadaraSource(
    name = "ManhwaHub",
    baseUrl = "https://manhwahub.to",
    mangaPath = "manhwa"
) {
    override val nsfw = true
}
