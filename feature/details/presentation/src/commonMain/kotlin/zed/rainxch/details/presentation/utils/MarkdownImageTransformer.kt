package zed.rainxch.details.presentation.utils

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

object MarkdownImageTransformer : ImageTransformer {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36 GitHubStore/1.8"

    // Hard cap on the decoded bitmap dimension. README images served at 4K+
    // resolutions used to be decoded full-size on the IO/Decoder dispatcher,
    // burning tens of megabytes per image and pushing GC pauses that
    // surfaced as Main-thread frame drops while the bitmap was uploaded.
    // 2048 px covers any display width on a phone or tablet at @3x density
    // and keeps the bitmap memory cost reasonable.
    private const val MAX_BITMAP_DIMENSION_PX = 2048

    private val networkHeaders =
        NetworkHeaders.Builder()
            .add("User-Agent", BROWSER_UA)
            .add(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            )
            .build()

    @Composable
    override fun transform(link: String): ImageData? {
        if (link.isBlank()) return null

        val normalizedLink =
            if (link.contains("github.com") && link.contains("/blob/")) {
                link
                    .replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            } else {
                link
            }

        if (!normalizedLink.startsWith("http://") &&
            !normalizedLink.startsWith("https://") &&
            !normalizedLink.startsWith("data:")
        ) {
            return null
        }

        val context = LocalPlatformContext.current
        val request =
            ImageRequest.Builder(context)
                .data(normalizedLink)
                .httpHeaders(networkHeaders)
                // Cap decoded bitmap so a 4K screenshot in a README doesn't
                // allocate 64 MB and stutter the frame on upload to GPU.
                // Coil rescales server-side response to fit before decoding.
                .size(MAX_BITMAP_DIMENSION_PX)
                // Stable cache key per normalized URL — same image appearing
                // multiple times (badges, logos) hits memory cache after the
                // first decode, skips the whole decode-and-upload path.
                .memoryCacheKey(normalizedLink)
                .diskCacheKey(normalizedLink)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(150)
                .build()

        val painter = rememberAsyncImagePainter(model = request)

        return ImageData(
            painter = painter,
            // Clamp displayed height so super-tall images (infographics,
            // long screenshots, vertical banners) don't take over the
            // scrollport — the previous unbounded heightIn caused 8000px
            // images to dominate the entire README composition.
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            contentDescription = "Image",
            contentScale = ContentScale.Fit,
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size = painter.intrinsicSize
}
