"""Draw the public atlas for the in-world pad diagram. No world or player input."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ENTITY = ROOT / "src/main/resources/assets/anyastorage/textures/entity"
STAMP = (256, 0, 281, 22)
INK = "#171729"
MINT = "#a2e6d3"
ROSE = "#e6afc7"
FONT = {
    "A": ("010", "101", "111", "101", "101"), "C": ("011", "100", "100", "100", "011"),
    "D": ("110", "101", "101", "101", "110"), "E": ("111", "100", "110", "100", "111"),
    "H": ("101", "101", "111", "101", "101"), "L": ("100", "100", "100", "100", "111"),
    "N": ("1001", "1101", "1011", "1001", "1001"), "P": ("110", "101", "110", "100", "100"),
    "S": ("011", "100", "010", "001", "110"), "T": ("111", "010", "010", "010", "010"),
    " ": ("0",) * 5,
}


def label(d, x, y, words, color, scale=1):
    for letter in words:
        rows = FONT[letter]
        for v, row in enumerate(rows):
            for u, pixel in enumerate(row):
                if pixel == "1":
                    xx, yy = x + u * scale, y + v * scale
                    d.rectangle((xx, yy, xx+scale-1, yy+scale-1), fill=color)
        x += (len(rows[0]) + 1) * scale


def bevel(d, box, step, color):
    x, y, xx, yy = box
    d.polygon([(x+step, y), (xx-step, y), (xx, y+step), (xx, yy-step),
               (xx-step, yy), (x+step, yy), (x, yy-step), (x, y+step)], fill=color)


def room(x, z, h=0):
    # Fixed room axes. Looking down on the same registered coordinates for every layout.
    return round(66 + 5.35*x - 1.60*z), round(98 + .42*x + 2.50*z - h)


# Native texel centers of the four original chest positions and the four optional room pads.
PADS = [room(x, 6) for x in (6, 10, 14, 18)] + [room(x, 14) for x in (6, 10, 22, 26)]


def study_plate():
    img = Image.new("RGBA", (256, 256))
    d = ImageDraw.Draw(img)
    for margin, step, c in [(0, 11, "#131421"), (3, 12, "#5f536c"), (5, 12, "#a896ac"),
                            (7, 12, "#393449"), (10, 12, "#685d78"), (12, 12, "#211e32")]:
        bevel(d, (margin, margin, 255-margin, 255-margin), step, c)
    for reflect in (False, True):
        def path(points, c, w=1):
            d.line([(255-x if reflect else x, y) for x,y in points], fill=c, width=w)
        path([(5,58),(5,19),(19,5),(63,5)], "#efd2d9", 2)
        path([(8,50),(8,23),(22,9),(46,9)], "#7bbaaf", 2)
        path([(4,201),(4,235),(20,251),(51,251)], "#b891a7", 2)
        for y in (29, 224): path([(6,y), (7,y)], "#f4d7d3", 2)

    # Header and screen rulings have their own shape language, distinct from the public room painting.
    for y in range(22, 202):
        shade = [(y<55, "#252337"), (y<83, "#29263d"), (y<133, "#2d2a41"),
                 (y<170, "#302c42"), (True, "#282639")]
        d.line((17, y, 238, y), fill=next(c for condition,c in shade if condition))
    d.line((23,54,232,54), fill="#55495f")
    d.line((25,57,102,57), fill="#64546a")
    bevel(d, (27,28,48,47), 3, "#3e535d")
    d.line([(32,36),(32,34),(35,31),(39,31),(42,34),(42,36)], fill="#a2e6d3", width=2)
    d.rectangle((31,36,43,43), fill="#9fd9c8")
    d.line((37,38,37,40), fill="#363548", width=2)
    label(d, 58, 33, "PAD PLAN", "#e9e0e9", 2)
    for i in range(8):
        x = 185 + (i % 4)*8
        y = 32 + (i // 4)*7
        d.rectangle((x,y,x+4,y+3), outline="#91829e")

    # Abstract rear/sidelights and low, cutaway front wall. None of the decoration represents a saved room.
    def polygon(vertices, color):
        d.polygon([room(*p) for p in vertices], fill=color)
    def edge(vertices, color, width=1):
        d.line([room(*p) for p in vertices], fill=color, width=width)
    corners = [(0,0), (31,0), (31,31), (0,31)]
    polygon([(0,0),(31,0),(31,0,30),(0,0,30)], "#40384f")
    polygon([(0,0),(0,31),(0,31,30),(0,0,30)], "#393546")
    for x in range(0,32,4):
        edge([(x,0,1), (x,0,28)], "#534b61")
    for z in range(0,32,4):
        edge([(0,z,0), (0,z,29)], "#4f485d")
    for h, c in [(29, "#c39aaa"), (27, "#75637c"), (7, "#534c64")]:
        edge([(0,31,h), (0,0,h), (31,0,h)], c)
    # A narrow star-chart instrument etched into the back wall rather than depicting a player's edits.
    edge([(4,0,18),(10,0,12),(15,0,18),(19,0,13),(23,0,18)], "#72617d")
    for x, h in [(4,18),(10,12),(15,18),(19,13),(23,18)]:
        xx, yy = room(x,0,h)
        d.rectangle((xx-1,yy-1,xx+1,yy+1), fill="#c7a5b6")
    polygon([(0,31),(31,31),(31,31,-7),(0,31,-7)], "#463e55")
    polygon([(31,0),(31,31),(31,31,-7),(31,0,-7)], "#332e43")
    polygon(corners, "#343448")
    for n in range(0,32,2):
        edge([(n,0),(n,31)], "#434459" if n%4==0 else "#3b3c50")
        edge([(0,n),(31,n)], "#47475a" if n%4==0 else "#3d3e52")
    edge(corners + [(0,0)], "#9490a3")
    edge([(0,31,-2),(31,31,-2),(31,0,-2)], "#625771")
    edge([(14,31,-3),(18,31,-3)], "#a5c9c1", 2)

    for cx, cy in PADS:
        # Open octagonal pad, readable even without color or a chest.
        poly=[(cx-9,cy-2),(cx-6,cy-5),(cx+7,cy-4),(cx+10,cy-2),
              (cx+7,cy+3),(cx+4,cy+4),(cx-7,cy+3)]
        d.polygon(poly, fill="#242538")
        d.line(poly+[poly[0]], fill="#ae92ab")
        d.line([(cx-6,cy+1),(cx-4,cy-2),(cx+5,cy-2)], fill="#6a5d78")
        d.line((cx+2,cy,cx+5,cy), fill="#917c96")
    # Orientation chevron and a quiet material legend make the overlay self-explanatory.
    d.polygon([(111,170),(115,166),(119,171),(115,169)], fill="#b9cbc3")
    d.line((115,170,115,175), fill="#829c99")
    d.line((24,202,231,202), fill="#5a4e64")
    d.rectangle((31,216,43,224), fill="#67576f", outline="#c0a2bc")
    d.line((32,219,42,219),fill=INK)
    d.point((37,220), fill=MINT)
    label(d, 50, 216, "CHEST", "#ccc2d4", 2)
    d.polygon([(133,219),(137,216),(147,216),(150,219),(147,223),(135,223)], outline="#c1a2b8")
    label(d, 157, 216, "PAD", "#c2b7ca", 2)
    return img


def chest_stamp():
    im = Image.new("RGBA", (25,22))
    d = ImageDraw.Draw(im)
    d.polygon([(0,17),(4,15),(22,15),(24,17),(18,21),(4,20)],fill="#222738")
    d.polygon([(1,7),(6,3),(19,3),(23,6),(23,16),(18,19),(3,17),(1,15)],fill="#182133")
    d.polygon([(3,9),(18,10),(18,17),(3,15)], fill="#7c7086")
    d.polygon([(18,10),(22,7),(22,15),(18,17)],fill="#51465e")
    d.line([(4,13),(4,15),(16,16)],fill="#b49eaf")
    d.polygon([(1,7),(6,2),(18,3),(22,5),(23,7),(19,11),(3,10)],fill="#a69aaf")
    d.polygon([(3,7),(7,3),(17,4),(19,5),(16,7)],fill="#e4cbd9")
    d.line([(2,9),(18,11),(23,7)],fill="#292639",width=2)
    d.line([(5,10),(8,10)],fill="#ebb8cd")
    d.line([(13,11),(16,11)],fill="#ebb8cd")
    d.polygon([(8,10),(10,8),(13,10),(12,13),(10,14),(8,12)],fill="#8fe0c8")
    d.point((10,10),fill="#ecf4df")
    return im


def create():
    atlas = Image.new("RGBA", (512,256))
    atlas.alpha_composite(study_plate())
    atlas.alpha_composite(chest_stamp(), (STAMP[0], STAMP[1]))
    return atlas


def compose(atlas, mask):
    image = atlas.crop((0,0,256,256))
    stamp = atlas.crop(STAMP)
    for index,(x,y) in enumerate(PADS):
        if mask & (1 << index):
            image.alpha_composite(stamp,(x-12,y-17))
    return image


def proof(target, atlas):
    # Only synthetic masks and the same two production textures are used in this offline sheet.
    images=[Image.open(ENTITY/"starter_room_study.png"), compose(atlas,0x0f), compose(atlas,0x8f)]
    board=Image.new("RGB",(1240,598),"#1a1926")
    d=ImageDraw.Draw(board)
    fonts=Path("C:/Windows/Fonts")
    def text(x,y,value,font,size,color):
        try: face=ImageFont.truetype(str(fonts/font),size)
        except OSError: face=ImageFont.truetype("DejaVuSans.ttf",size)
        d.text((x,y), value, font=face,fill=color)
    text(36,25,"The pocket pad plan","georgia.ttf",39,"#f0e5e9")
    text(36,82,"Home Void Cube   /   offline asset study","segoeui.ttf",17,"#c1afbF")
    d.line((36,118,1204,118), fill="#504453")
    captions=[("Public illustration","A shared painting on the focused face."),
              ("Four installed chests","The four optional pads are open."),
              ("Five installed chests","One chest added on the far-right pad.")]
    for i,(im,(title,caption)) in enumerate(zip(images,captions)):
        x=36+i*397
        d.rounded_rectangle((x,140,x+372,493),radius=10,fill="#272533")
        art=im.convert("RGBA").resize((320,320),Image.Resampling.NEAREST)
        board.paste(art,(x+26,156),art)
        text(x,507,title,"segoeuib.ttf",21,"#eee1e7")
        text(x,548,caption,"segoeui.ttf",16,"#bcb0c0")
    target.parent.mkdir(parents=True,exist_ok=True)
    board.save(target)


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proof",type=Path)
    args=parser.parse_args()
    atlas=create()
    atlas.save(ENTITY/"pocket_pad_plan.png",optimize=True)
    if args.proof: proof(args.proof,atlas)
