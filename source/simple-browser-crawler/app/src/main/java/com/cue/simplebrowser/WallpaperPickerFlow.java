package com.cue.simplebrowser;

/** Pure selection flow shared by the wallpaper chooser and its regression tests. */
final class WallpaperPickerFlow {
    interface PreviewLoader<T> {
        T load(String wallpaperId) throws Exception;
    }

    static final class Session {
        final WallpaperRotation.SelectionState selection;

        private Session(WallpaperRotation.SelectionState selection) {
            this.selection = selection;
        }

        java.util.List<WallpaperRotation.Wallpaper> wallpapers() {
            return WallpaperRotation.all();
        }

        <T> SelectionResult<T> select(String wallpaperId, PreviewLoader<T> loader) {
            if (WallpaperRotation.find(wallpaperId) == null || loader == null) {
                return SelectionResult.failed(selection, null);
            }
            try {
                T preview = loader.load(wallpaperId);
                if (preview == null) throw new IllegalStateException("Preview loader returned no image");
                return SelectionResult.selected(selection.selectManually(wallpaperId), preview, false);
            } catch (Exception requestedError) {
                if (WallpaperRotation.DEFAULT_ID.equals(wallpaperId)) {
                    return SelectionResult.failed(selection, requestedError);
                }
                try {
                    T fallback = loader.load(WallpaperRotation.DEFAULT_ID);
                    if (fallback == null) throw new IllegalStateException("Fallback loader returned no image");
                    return SelectionResult.selected(
                            selection.selectManually(WallpaperRotation.DEFAULT_ID), fallback, true);
                } catch (Exception fallbackError) {
                    return SelectionResult.failed(selection, fallbackError);
                }
            }
        }
    }

    static final class SelectionResult<T> {
        final WallpaperRotation.SelectionState selection;
        final T preview;
        final boolean successful;
        final boolean usedFallback;
        final Exception error;

        private SelectionResult(WallpaperRotation.SelectionState selection, T preview,
                boolean successful, boolean usedFallback, Exception error) {
            this.selection = selection;
            this.preview = preview;
            this.successful = successful;
            this.usedFallback = usedFallback;
            this.error = error;
        }

        private static <T> SelectionResult<T> selected(
                WallpaperRotation.SelectionState selection, T preview, boolean usedFallback) {
            return new SelectionResult<>(selection, preview, true, usedFallback, null);
        }

        private static <T> SelectionResult<T> failed(
                WallpaperRotation.SelectionState selection, Exception error) {
            return new SelectionResult<>(selection, null, false, false, error);
        }
    }

    private WallpaperPickerFlow() { }

    static Session open(WallpaperRotation.SelectionState selection) {
        WallpaperRotation.SelectionState safeSelection = selection;
        if (safeSelection == null || safeSelection.selectedId == null) {
            safeSelection = new WallpaperRotation.SelectionState(
                    WallpaperRotation.DEFAULT_ID,
                    safeSelection != null && safeSelection.automatic,
                    safeSelection == null ? null : safeSelection.automaticDate,
                    safeSelection == null ? null : safeSelection.lastAutomaticId);
        }
        return new Session(safeSelection);
    }
}
