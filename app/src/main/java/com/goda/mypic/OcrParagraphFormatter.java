package com.goda.mypic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于 OCR 行框与 token 几何信息的段落检测与文本拼接器。
 *
 * <p>用于将版面排序后的视觉行合并为自然段落：同段落折行直接拼接（英文/数字单词间补空格），
 * 仅在段落边界（段间距、首行缩进、未排满行尾、字号/分栏变化、列表项、键值行等）保留换行符。</p>
 */
public final class OcrParagraphFormatter {

    private static final float MAX_FONT_HEIGHT_RATIO = 1.38f;
    private static final float HARD_VERTICAL_GAP_RATIO = 2.20f;
    private static final float MIN_PROSE_COLUMN_WIDTH_RATIO = 0.34f;
    private static final int MIN_PROSE_COLUMN_LINE_CHARS = 12;

    private static final Pattern LIST_MARKER_PATTERN = Pattern.compile(
            "^(?:[•·◦▪▫▸►✓✔✕✗□☑☐★☆➤➔①-⑳]"
                    + "|[-*]\\s+"
                    + "|\\d{1,2}(?:\\.\\s+|\\.[^\\x00-\\x7F]|、|[)）]\\s*)"
                    + "|[一二三四五六七八九十]{1,3}、"
                    + "|[（(\\[【](?:\\d{1,2}|[一二三四五六七八九十]{1,3})[）)\\]】]).*"
    );

    private OcrParagraphFormatter() {
    }

    /** 视觉行中的单个可见 token 几何框（自上而下像素坐标）。 */
    public static final class TokenBox {
        public final String text;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final boolean precededBySpace;

        public TokenBox(
                String text,
                float left,
                float top,
                float right,
                float bottom,
                boolean precededBySpace
        ) {
            this.text = text != null ? text : "";
            this.left = Math.min(left, right);
            this.right = Math.max(left, right);
            this.top = Math.min(top, bottom);
            this.bottom = Math.max(top, bottom);
            this.precededBySpace = precededBySpace;
        }

        float width() {
            return Math.max(0f, right - left);
        }

        float height() {
            return Math.max(0f, bottom - top);
        }
    }

    /** 单个视觉行的文本与几何摘要。 */
    public static final class TextLine {
        public final String text;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float fontHeight;
        public final float firstWrapUnitWidth;
        public final boolean hasTabularGap;
        public final int visibleCharCount;

        public TextLine(
                String text,
                float left,
                float top,
                float right,
                float bottom,
                float fontHeight,
                float firstWrapUnitWidth,
                boolean hasTabularGap
        ) {
            this.text = text != null ? text.trim() : "";
            this.left = Math.min(left, right);
            this.right = Math.max(left, right);
            this.top = Math.min(top, bottom);
            this.bottom = Math.max(top, bottom);
            float rawHeight = Math.max(1f, this.bottom - this.top);
            this.fontHeight = fontHeight > 0f ? fontHeight : rawHeight;
            this.firstWrapUnitWidth = Math.max(0f, firstWrapUnitWidth);
            this.hasTabularGap = hasTabularGap;
            this.visibleCharCount = countVisibleChars(this.text);
        }

        /** 由行文本与有序 token 框构造视觉行几何信息。 */
        public static TextLine fromTokens(String rawText, List<TokenBox> tokens) {
            String trimmed = rawText != null ? rawText.trim() : "";
            if (trimmed.isEmpty()) return null;

            List<TokenBox> validTokens = new ArrayList<>();
            if (tokens != null) {
                for (TokenBox token : tokens) {
                    if (token != null && token.width() > 0f && token.height() > 0f) {
                        validTokens.add(token);
                    }
                }
            }
            if (validTokens.isEmpty()) {
                return new TextLine(trimmed, 0f, 0f, 0f, 0f, 0f, 0f, false);
            }

            float left = Float.MAX_VALUE;
            float top = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            List<Float> heights = new ArrayList<>(validTokens.size());
            for (TokenBox token : validTokens) {
                left = Math.min(left, token.left);
                top = Math.min(top, token.top);
                right = Math.max(right, token.right);
                bottom = Math.max(bottom, token.bottom);
                heights.add(token.height());
            }

            // 用 token 高度的 75 分位作为字高，避免单行整框因拍照轻微倾斜被拉高。
            float fontHeight = percentile(heights, 0.75f);
            if (fontHeight <= 0f) fontHeight = Math.max(1f, bottom - top);

            int visibleCount = Math.max(1, countVisibleChars(trimmed));
            float avgGlyphWidth = clamp((right - left) / visibleCount, fontHeight * 0.35f, fontHeight * 1.35f);
            float firstUnitWidth = computeFirstWrapUnitWidth(validTokens, fontHeight, avgGlyphWidth);
            boolean tabularGap = detectTabularGap(validTokens, fontHeight);

            return new TextLine(trimmed, left, top, right, bottom, fontHeight, firstUnitWidth, tabularGap);
        }

        boolean hasBounds() {
            return right > left && bottom > top && fontHeight > 0f;
        }

        float width() {
            return Math.max(0f, right - left);
        }

        float rawBoxHeight() {
            return Math.max(1f, bottom - top);
        }

        float centerX() {
            return (left + right) * 0.5f;
        }

        float centerY() {
            return (top + bottom) * 0.5f;
        }

        float estimatedCharWidth() {
            if (visibleCharCount <= 0 || width() <= 0f) return fontHeight;
            return clamp(width() / visibleCharCount, fontHeight * 0.38f, fontHeight * 1.30f);
        }
    }

    /** 将按阅读顺序排列的视觉行检测段落并重建文本，同段不添加换行。 */
    public static String format(List<TextLine> rawLines, float pageWidth) {
        if (rawLines == null || rawLines.isEmpty()) return "";

        List<TextLine> lines = new ArrayList<>(rawLines.size());
        for (TextLine line : rawLines) {
            if (line != null && !line.text.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) return "";
        if (lines.size() == 1) return lines.get(0).text;

        int n = lines.size();
        boolean[] sameVerticalBand = computeVerticalBandContinuity(lines);
        VerticalStats[] bandStats = computeBandStats(lines, sameVerticalBand);

        boolean[] forceBreakAfter = new boolean[n - 1];
        boolean[] allowTiltedInlineMerge = new boolean[n - 1];
        for (int i = 0; i < n - 1; i++) {
            TextLine prev = lines.get(i);
            TextLine next = lines.get(i + 1);
            if (!prev.hasBounds() || !next.hasBounds()) {
                forceBreakAfter[i] = true;
                continue;
            }

            float avgH = 0.5f * (prev.fontHeight + next.fontHeight);
            if (isTiltedSameRowContinuation(prev, next, avgH)) {
                allowTiltedInlineMerge[i] = true;
                continue;
            }

            if (isHardParagraphBreak(prev, next, i, sameVerticalBand[i], bandStats[i], avgH)) {
                forceBreakAfter[i] = true;
            }
        }

        boolean[] mergeWithNext = new boolean[n - 1];
        int blockStart = 0;
        while (blockStart < n) {
            int blockEnd = blockStart;
            while (blockEnd < n - 1 && !forceBreakAfter[blockEnd]) {
                blockEnd++;
            }
            evaluateMarginBlockMerges(
                    lines,
                    blockStart,
                    blockEnd,
                    pageWidth,
                    allowTiltedInlineMerge,
                    mergeWithNext
            );
            blockStart = blockEnd + 1;
        }

        StringBuilder out = new StringBuilder(lines.get(0).text);
        for (int i = 0; i < n - 1; i++) {
            String nextText = lines.get(i + 1).text;
            if (mergeWithNext[i]) {
                joinSameParagraphLine(out, nextText);
            } else {
                out.append('\n').append(nextText);
            }
        }
        return out.toString().trim();
    }

    private static boolean[] computeVerticalBandContinuity(List<TextLine> lines) {
        boolean[] sameBand = new boolean[lines.size() - 1];
        for (int i = 0; i < lines.size() - 1; i++) {
            TextLine prev = lines.get(i);
            TextLine next = lines.get(i + 1);
            if (!prev.hasBounds() || !next.hasBounds()) continue;

            float minH = Math.min(prev.fontHeight, next.fontHeight);
            float maxH = Math.max(prev.fontHeight, next.fontHeight);
            if (maxH / minH > MAX_FONT_HEIGHT_RATIO) continue;

            float avgH = 0.5f * (minH + maxH);
            if (next.top < prev.top - avgH * 0.45f) continue;

            float overlapX = Math.min(prev.right, next.right) - Math.max(prev.left, next.left);
            float minWidth = Math.min(prev.width(), next.width());
            boolean sharesHorizontalBand = overlapX >= Math.min(minWidth * 0.25f, avgH * 2.0f)
                    || isTiltedSameRowContinuation(prev, next, avgH);
            if (!sharesHorizontalBand) continue;

            float gap = effectiveVerticalGap(prev, next);
            if (gap > avgH * HARD_VERTICAL_GAP_RATIO) continue;

            sameBand[i] = true;
        }
        return sameBand;
    }

    private static VerticalStats[] computeBandStats(List<TextLine> lines, boolean[] sameBand) {
        VerticalStats[] statsByPair = new VerticalStats[lines.size() - 1];
        int start = 0;
        while (start < lines.size() - 1) {
            if (!sameBand[start]) {
                start++;
                continue;
            }
            int end = start;
            while (end < lines.size() - 1 && sameBand[end]) {
                end++;
            }

            List<Float> gaps = new ArrayList<>();
            List<Float> steps = new ArrayList<>();
            for (int i = start; i < end; i++) {
                TextLine prev = lines.get(i);
                TextLine next = lines.get(i + 1);
                float avgH = 0.5f * (prev.fontHeight + next.fontHeight);
                if (isTiltedSameRowContinuation(prev, next, avgH)) continue;

                float gap = effectiveVerticalGap(prev, next);
                float step = next.centerY() - prev.centerY();
                if (gap >= -avgH * 0.25f && gap <= avgH * 1.65f) {
                    gaps.add(gap);
                }
                if (step >= avgH * 0.70f && step <= avgH * 2.65f) {
                    steps.add(step);
                }
            }

            VerticalStats bandStats = new VerticalStats(
                    gaps.size() >= 2 ? percentile(gaps, 0.35f) : Float.NaN,
                    steps.size() >= 2 ? percentile(steps, 0.35f) : Float.NaN,
                    gaps.size()
            );
            for (int i = start; i < end; i++) {
                statsByPair[i] = bandStats;
            }
            start = end + 1;
        }
        return statsByPair;
    }

    private static boolean isHardParagraphBreak(
            TextLine prev,
            TextLine next,
            int pairIndex,
            boolean inSameBand,
            VerticalStats stats,
            float avgH
    ) {
        if (!inSameBand) return true;

        float gap = effectiveVerticalGap(prev, next);
        float step = next.centerY() - prev.centerY();
        if (stats != null && stats.sampleCount >= 2
                && !Float.isNaN(stats.typicalGap)
                && !Float.isNaN(stats.typicalStep)) {
            if (gap > Math.max(avgH * 0.68f, stats.typicalGap + avgH * 0.45f)
                    && step > stats.typicalStep * 1.30f) {
                return true;
            }
        } else if (gap > avgH * 0.92f) {
            return true;
        }

        if (prev.hasTabularGap || next.hasTabularGap) return true;
        if (isShortKeyValueLine(prev.text) && isShortKeyValueLine(next.text)) return true;
        if (startsWithListMarker(next.text)) return true;

        float charW = Math.max(prev.estimatedCharWidth(), next.estimatedCharWidth());
        float shiftRight = next.left - prev.left;
        if (shiftRight > charW * 1.15f) {
            boolean possibleHangingIndent = startsWithListMarker(prev.text)
                    && shiftRight <= avgH * 4.2f;
            boolean possibleCenteredLastLine = isCenteredTightLastLine(prev, next, charW);
            if (!possibleHangingIndent && !possibleCenteredLastLine) {
                return true;
            }
        } else if (-shiftRight > charW * 4.8f) {
            return true;
        }

        return false;
    }

    private static void evaluateMarginBlockMerges(
            List<TextLine> lines,
            int blockStart,
            int blockEnd,
            float pageWidth,
            boolean[] allowTiltedInlineMerge,
            boolean[] mergeWithNext
    ) {
        if (blockEnd <= blockStart) return;

        float blockLeft = Float.MAX_VALUE;
        float blockRight = -Float.MAX_VALUE;
        int maxLineChars = 0;

        for (int i = blockStart; i <= blockEnd; i++) {
            TextLine line = lines.get(i);
            blockLeft = Math.min(blockLeft, line.left);
            blockRight = Math.max(blockRight, line.right);
            maxLineChars = Math.max(maxLineChars, line.visibleCharCount);
        }

        float blockWidth = Math.max(0f, blockRight - blockLeft);
        float bodyLeft = blockLeft;
        boolean startsAsListBlock = startsWithListMarker(lines.get(blockStart).text);
        boolean isProseColumn = pageWidth <= 0f
                || blockWidth >= pageWidth * MIN_PROSE_COLUMN_WIDTH_RATIO
                || maxLineChars >= MIN_PROSE_COLUMN_LINE_CHARS;
        int blockLineCount = blockEnd - blockStart + 1;

        for (int i = blockStart; i < blockEnd; i++) {
            if (allowTiltedInlineMerge[i]) {
                mergeWithNext[i] = true;
                continue;
            }

            TextLine prev = lines.get(i);
            TextLine next = lines.get(i + 1);
            float avgH = 0.5f * (prev.fontHeight + next.fontHeight);
            float charW = Math.max(prev.estimatedCharWidth(), next.estimatedCharWidth());

            if (blockLineCount >= 3
                    && !startsAsListBlock
                    && prev.left >= bodyLeft + charW * 1.15f
                    && next.left >= bodyLeft + charW * 1.15f
                    && !isCenteredTightLastLine(prev, next, charW)) {
                mergeWithNext[i] = false;
                continue;
            }

            float shiftRight = next.left - prev.left;
            if (shiftRight > charW * 1.15f) {
                if (isCenteredContinuation(prev, next, blockRight, blockWidth, charW)) {
                    mergeWithNext[i] = true;
                    continue;
                }
                boolean allowHanging = startsWithListMarker(prev.text)
                        && shiftRight <= avgH * 4.2f;
                if (!allowHanging) {
                    mergeWithNext[i] = false;
                    continue;
                }
            }

            boolean centeredSymmetricShift = Math.abs(prev.centerX() - next.centerX()) <= charW * 0.9f
                    && ((next.left - prev.left > charW * 0.7f && prev.right - next.right > charW * 0.7f)
                            || (prev.left - next.left > charW * 0.7f && next.right - prev.right > charW * 0.7f));
            if (centeredSymmetricShift
                    && !isCenteredContinuation(prev, next, blockRight, blockWidth, charW)) {
                mergeWithNext[i] = false;
                continue;
            }

            if (!isProseColumn && !endsWithContinuationPunct(prev.text)) {
                mergeWithNext[i] = false;
                continue;
            }

            float unusedRight = Math.max(0f, blockRight - prev.right);
            float nextUnitW = next.firstWrapUnitWidth > 0f ? next.firstWrapUnitWidth : charW;
            if (endsWithTerminalPunct(prev.text)) {
                float slack = endsWithStrongSentenceEnd(prev.text) ? charW * 0.45f : charW * 0.90f;
                float threshold = Math.max(charW * 1.25f, nextUnitW + slack);
                mergeWithNext[i] = unusedRight <= threshold;
            } else {
                boolean veryShortStandalone = prev.width() < blockWidth * 0.58f
                        && unusedRight > charW * 2.2f;
                float threshold = Math.max(charW * 2.5f, nextUnitW + charW * 1.1f);
                mergeWithNext[i] = !veryShortStandalone && unusedRight <= threshold;
            }
        }
    }

    private static boolean isCenteredContinuation(
            TextLine prev,
            TextLine next,
            float blockRight,
            float blockWidth,
            float charW
    ) {
        return isCenteredTightLastLine(prev, next, charW)
                && (blockRight - prev.right) <= charW * 1.5f
                && prev.width() >= blockWidth * 0.80f;
    }

    private static boolean isCenteredTightLastLine(TextLine prev, TextLine next, float charW) {
        return Math.abs(prev.centerX() - next.centerX()) <= charW * 1.2f
                && next.left > prev.left + charW * 0.7f
                && next.right < prev.right - charW * 1.0f
                && !endsWithStrongSentenceEnd(prev.text);
    }

    private static boolean isTiltedSameRowContinuation(TextLine prev, TextLine next, float avgH) {
        float gapX = next.left - prev.right;
        return gapX >= -avgH * 0.35f
                && gapX <= avgH * 1.80f
                && next.top < prev.bottom - avgH * 0.20f
                && Math.abs(next.centerY() - prev.centerY()) <= avgH * 0.70f;
    }

    private static float effectiveVerticalGap(TextLine prev, TextLine next) {
        float avgH = 0.5f * (prev.fontHeight + next.fontHeight);
        float centerStepGap = (next.centerY() - prev.centerY()) - avgH;
        boolean tiltedBox = prev.rawBoxHeight() > prev.fontHeight * 1.45f
                || next.rawBoxHeight() > next.fontHeight * 1.45f;
        return tiltedBox ? centerStepGap : (next.top - prev.bottom);
    }

    private static void joinSameParagraphLine(StringBuilder out, String nextText) {
        String trimmedNext = nextText != null ? nextText.trim() : "";
        if (trimmedNext.isEmpty()) return;
        if (out.length() == 0) {
            out.append(trimmedNext);
            return;
        }

        while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.deleteCharAt(out.length() - 1);
        }
        if (out.length() == 0) {
            out.append(trimmedNext);
            return;
        }

        int lastCp = out.codePointBefore(out.length());
        int firstCp = trimmedNext.codePointAt(0);
        if (needsWordSpaceAtWrap(lastCp, firstCp)) {
            out.append(' ');
        }
        out.append(trimmedNext);
    }

    private static boolean needsWordSpaceAtWrap(int lastCp, int firstCp) {
        if (isCjk(lastCp) || isCjk(firstCp)) return false;
        if (isOpeningPunctuation(lastCp) || isTightConnector(lastCp)) return false;
        if (isClosingPunctuation(firstCp) || isTightConnector(firstCp)) return false;

        if (lastCp == '.') {
            // URL / 包名 / 文件名（如 com.goda）在点号后接小写字母时不插空格
            return Character.isUpperCase(firstCp) || Character.isDigit(firstCp);
        }

        boolean lastJoinable = isLatinOrDigit(lastCp) || ",;!?)]}%\"'".indexOf(lastCp) >= 0;
        boolean firstJoinable = isLatinOrDigit(firstCp) || "([{\"'".indexOf(firstCp) >= 0;
        return lastJoinable && firstJoinable;
    }

    private static float computeFirstWrapUnitWidth(
            List<TokenBox> tokens,
            float fontHeight,
            float avgGlyphWidth
    ) {
        if (tokens == null || tokens.isEmpty()) return avgGlyphWidth;

        TokenBox first = tokens.get(0);
        float startLeft = first.left;
        float endRight = first.right;
        boolean seenCore = false;
        boolean latinMode = false;

        for (int i = 0; i < tokens.size(); i++) {
            TokenBox token = tokens.get(i);
            if (i > 0 && token.precededBySpace) break;

            String text = token.text.trim();
            if (text.isEmpty()) continue;

            int cp = text.codePointAt(0);
            if (!seenCore && isOpeningPunctuation(cp)) {
                endRight = token.right;
                continue;
            }

            if (!seenCore) {
                seenCore = true;
                if (isCjk(cp)) {
                    endRight = estimateSingleUnitEnd(token, text, 1);
                    for (int j = i + 1; j < tokens.size(); j++) {
                        TokenBox punct = tokens.get(j);
                        if (punct.precededBySpace) break;
                        String pt = punct.text.trim();
                        if (pt.isEmpty()) break;
                        int pcp = pt.codePointAt(0);
                        if (isClosingPunctuation(pcp)) {
                            endRight = punct.right;
                        } else {
                            break;
                        }
                    }
                    break;
                }

                latinMode = isLatinOrDigit(cp);
                endRight = estimateFirstSpaceBoundEnd(token, text);
                if (!latinMode || text.indexOf(' ') >= 0) break;
                continue;
            }

            if (latinMode) {
                if (isCjk(cp)) break;
                if (isClosingPunctuation(cp)) {
                    endRight = token.right;
                    break;
                }
                endRight = estimateFirstSpaceBoundEnd(token, text);
                if ((cp == '/' || cp == '-') && (endRight - startLeft) > fontHeight * 4.0f) {
                    break;
                }
                if (text.indexOf(' ') >= 0) break;
            } else {
                break;
            }
        }

        return Math.max(avgGlyphWidth, Math.max(0f, endRight - startLeft));
    }

    private static float estimateSingleUnitEnd(TokenBox token, String text, int unitCount) {
        int totalCp = Math.max(1, text.codePointCount(0, text.length()));
        if (totalCp <= unitCount) return token.right;
        return token.left + token.width() * (unitCount / (float) totalCp);
    }

    private static float estimateFirstSpaceBoundEnd(TokenBox token, String text) {
        int spaceIdx = text.indexOf(' ');
        if (spaceIdx < 0) return token.right;
        int totalCp = Math.max(1, text.codePointCount(0, text.length()));
        int prefixCp = Math.max(1, text.codePointCount(0, spaceIdx));
        return token.left + token.width() * (prefixCp / (float) totalCp);
    }

    private static boolean detectTabularGap(List<TokenBox> tokens, float fontHeight) {
        if (tokens.size() < 2) return false;
        List<Float> positiveGaps = new ArrayList<>();
        float maxGap = 0f;
        for (int i = 1; i < tokens.size(); i++) {
            float gap = tokens.get(i).left - tokens.get(i - 1).right;
            if (gap > 0f && Float.isFinite(gap)) {
                positiveGaps.add(gap);
                if (gap > maxGap) maxGap = gap;
            }
        }
        if (maxGap < fontHeight * 2.6f) return false;
        float medianGap = positiveGaps.size() >= 3 ? percentile(positiveGaps, 0.50f) : 0f;
        return medianGap <= 0f || maxGap >= medianGap * 3.5f;
    }

    private static boolean startsWithListMarker(String text) {
        return text != null && LIST_MARKER_PATTERN.matcher(text).matches();
    }

    private static boolean isShortKeyValueLine(String text) {
        if (text == null || countVisibleChars(text) > 28) return false;
        int colon = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ':' || ch == '：') {
                colon = i;
                break;
            }
        }
        if (colon <= 0 || colon >= text.length() - 1) return false;
        if (text.startsWith("//", colon + 1)) return false;

        String label = text.substring(0, colon).trim();
        int labelLen = countVisibleChars(label);
        if (labelLen < 1 || labelLen > 8) return false;

        boolean hasLetterOrCjk = false;
        for (int offset = 0; offset < label.length(); ) {
            int cp = label.codePointAt(offset);
            if ("，。！？,.；;()（）".indexOf(cp) >= 0) return false;
            if (Character.isLetter(cp)) hasLetterOrCjk = true;
            offset += Character.charCount(cp);
        }
        return hasLetterOrCjk;
    }

    private static boolean endsWithTerminalPunct(String text) {
        if (text == null || text.isEmpty()) return false;
        int cp = text.codePointBefore(text.length());
        return "。！？；：.!?;:…~～”’」』】》）)".indexOf(cp) >= 0;
    }

    private static boolean endsWithStrongSentenceEnd(String text) {
        if (text == null || text.isEmpty()) return false;
        int offset = text.length();
        while (offset > 0) {
            int cp = text.codePointBefore(offset);
            if ("”’」』】》）)\"'".indexOf(cp) >= 0) {
                offset -= Character.charCount(cp);
                continue;
            }
            return "。！？.!?…".indexOf(cp) >= 0;
        }
        return false;
    }

    private static boolean endsWithContinuationPunct(String text) {
        if (text == null || text.isEmpty()) return false;
        int cp = text.codePointBefore(text.length());
        return "，、,(（[［【《「『“‘-‐‑‒–—".indexOf(cp) >= 0;
    }

    private static boolean isOpeningPunctuation(int codePoint) {
        return "([{<（［｛《〈「『【〔〖〘〚“‘".indexOf(codePoint) >= 0;
    }

    private static boolean isClosingPunctuation(int codePoint) {
        return ")]}>）］｝》〉」』】〕〗〙〛，。！？；：、,.!?;:%％”’".indexOf(codePoint) >= 0;
    }

    private static boolean isTightConnector(int codePoint) {
        return "-‐‑‒–—_/\\'’·•@#".indexOf(codePoint) >= 0;
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int countVisibleChars(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) count++;
            offset += Character.charCount(cp);
        }
        return count;
    }

    private static float percentile(List<Float> values, float q) {
        if (values == null || values.isEmpty()) return 0f;
        List<Float> sorted = new ArrayList<>(values.size());
        for (Float v : values) {
            if (v != null && Float.isFinite(v)) sorted.add(v);
        }
        if (sorted.isEmpty()) return 0f;
        Collections.sort(sorted);
        int idx = Math.round((sorted.size() - 1) * clamp(q, 0f, 1f));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class VerticalStats {
        final float typicalGap;
        final float typicalStep;
        final int sampleCount;

        VerticalStats(float typicalGap, float typicalStep, int sampleCount) {
            this.typicalGap = typicalGap;
            this.typicalStep = typicalStep;
            this.sampleCount = sampleCount;
        }
    }
}
