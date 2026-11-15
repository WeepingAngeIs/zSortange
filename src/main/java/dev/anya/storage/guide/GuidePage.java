package dev.anya.storage.guide;

import java.util.*;

/** Literal, bounded guide content. No click events, URLs, player inventories or private image IDs. */
public record GuidePage(String chapter,String title,String art,String body,String note,String recipe,List<Cell> cells) {
    public record Cell(String id,String label) {
        public Cell { if(id.length()>128||label.length()>128)throw new IllegalArgumentException("Guide cell too long"); }
    }
    public GuidePage {
        Objects.requireNonNull(chapter);Objects.requireNonNull(title);Objects.requireNonNull(art);Objects.requireNonNull(body);Objects.requireNonNull(note);Objects.requireNonNull(recipe);
        if(chapter.length()>32||title.length()>64||art.length()>128||body.length()>2048||note.length()>256||recipe.length()>160||cells.size()>9)throw new IllegalArgumentException("Guide page exceeds bounds");
        cells=List.copyOf(cells);
    }
    public static GuidePage text(String chapter,String title,String art,String body,String note){return new GuidePage(chapter,title,art,body,note,"",List.of());}
}
