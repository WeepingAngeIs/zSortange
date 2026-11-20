"""Paint the fixed public window and its single closed chest sprite, without world input."""
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw


TEXTURE = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/textures/entity/framed_chest_still.png"


def room(x, z, height=0):
    return round(56 + 6*x - 2*z), round(96 + x + 3*z - height)


def back_wall():
    """An original celestial triptych, painted flat before projection onto the back wall."""
    wall = Image.new("RGB", (224, 90), "#44364f")
    d = ImageDraw.Draw(wall)
    for y in range(0, 90, 9):
        d.line((0, y, 223, y), fill="#3b3247")
        for x in range((y//9 % 2)*12, 224, 24):
            d.line((x, y, x, y+8), fill="#393044")
            d.line((x+1, y+1, x+12, y+1), fill="#4d3d55")

    # A single sky crosses the three panes. Stepped curves keep the leadwork crisp in game.
    sky = Image.new("RGB", wall.size)
    sky_d = ImageDraw.Draw(sky)
    for y0, y1, color in [(0, 18, "#202741"), (19, 33, "#292b49"), (34, 43, "#35304f"),
                           (44, 55, "#463956"), (56, 70, "#584159"), (71, 89, "#6d4e65")]:
        sky_d.rectangle((0, y0, 223, y1), fill=color)
    sky_d.polygon([(14,54),(55,38),(94,35),(141,22),(192,22),(159,28),(133,28),
                   (106,37),(70,42),(42,45)], fill="#3b3554")
    rng = Random(432)
    for _ in range(125):
        x, y = rng.randrange(14, 190), rng.randrange(13, 66)
        sky_d.point((x, y), fill=rng.choice(("#72647e", "#87778e", "#b099a3")))
    for x, y in [(41,34), (79,21), (104,43), (166,37)]:
        sky_d.line((x-2,y,x+2,y),fill="#8f839b")
        sky_d.line((x,y-2,x,y+2),fill="#b4acb6")
        sky_d.point((x,y),fill="#e9ddcd")
    # Ringed moon, lit from gauzy rose to amber. Its ring crosses the planet's lower limb.
    sky_d.ellipse((111,23,131,43),fill="#d29da2")
    sky_d.pieslice((111,23,131,43),235,74,fill="#e3baaf")
    sky_d.polygon([(112,32),(129,26),(131,29),(112,36)],fill="#dab2a9")
    sky_d.line([(103,39),(111,34),(117,32)],fill="#a3849c")
    sky_d.line([(124,29),(133,27),(139,26),(140,27),(134,31),(117,38),(104,41),(103,40)],
               fill="#efd1b9")
    sky_d.line([(108,42),(117,40),(129,35)],fill="#9d849e")
    sky_d.polygon([(0,63),(17,52),(27,53),(38,47),(48,51),(60,49),(71,56),(87,48),(96,50),
                   (112,45),(124,50),(137,48),(153,54),(168,46),(188,53),(210,47),(224,46),
                   (224,76),(0,76)],fill="#564463")
    sky_d.polygon([(0,67),(23,61),(36,64),(53,61),(70,65),(91,62),(113,57),(133,63),
                   (148,58),(172,64),(193,61),(224,64),(224,78),(0,78)],fill="#3b354e")
    for center, half, peak, spring in [(50,25,24,38), (102,25,14,29), (154,25,24,38)]:
        def arch(shrink):
            l,r = center-half+shrink, center+half-shrink
            base = 73-shrink
            return [(l,base),(l,spring+shrink),(l+4,spring-4+shrink),
                    (center-8,peak+4+shrink),(center,peak+shrink),(center+8,peak+4+shrink),
                    (r-4,spring-4+shrink),(r,spring+shrink),(r,base)]
        for shrink,color in [(-2,"#282436"),(-1,"#72546b"),(0,"#b58c86"),(1,"#72566c"),(2,"#332f47")]:
            d.polygon(arch(shrink),fill=color)
        mask = Image.new("L", wall.size)
        ImageDraw.Draw(mask).polygon(arch(3),fill=255)
        wall.paste(sky,(0,0),mask)
        for offset in (-12,12):
            x=center+offset
            d.line((x,peak+10,x,69),fill="#443e55")
            d.line((x+1,peak+10,x+1,69),fill="#6a5969")
        d.line((center-half+4,53,center+half-4,53),fill="#594b60")
    for y,color in [(74,"#b08c87"),(75,"#7d6273"),(76,"#302c40"),(82,"#534250"),
                    (84,"#92727c"),(85,"#6f566b"),(86,"#302c40"),(89,"#393246")]:
        d.line((0,y,223,y),fill=color)
    return wall


def room_painting():
    img = Image.new("RGB", (256,256), "#282638")
    d = ImageDraw.Draw(img)
    def polygon(vertices, color):
        d.polygon([room(*pt) for pt in vertices],fill=color)
    def edge(vertices, color):
        d.line([room(*pt) for pt in vertices],fill=color)
    # Left wall in shadow, interrupted by two recessed, bevelled panels.
    polygon([(0,0),(0,44),(0,44,90),(0,0,90)], "#3a354c")
    for z in range(0,44,4):
        edge([(0,z,0),(0,z,88)],"#43384e")
    for z0 in (2,15,28):
        for inset,col in [(0,"#55465d"),(1,"#2b2b40"),(2,"#423c53")]:
            polygon([(0,z0+inset,14+inset),(0,z0+11-inset,14+inset),
                     (0,z0+11-inset,68-inset),(0,z0+6,75-inset),(0,z0+inset,68-inset)],col)
    for h,col in [(82,"#847080"),(80,"#58495d"),(9,"#66586c"),(7,"#2a293d"),(2,"#726073")]:
        edge([(0,0,h),(0,44,h)],col)
    wall = back_wall()
    for x in range(wall.width):
        img.paste(wall.crop((x,0,x+1,90)),(56+x,round(96+x/6)-90))
    # Stone pavers across the plane are independent of all eight chest bits.
    colors = ("#494255", "#494255", "#4b4356", "#484254", "#4d4557")
    for z in range(0,52,4):
        for x in range(-4 if z%8 else 0,40,8):
            x0 = max(0,x)
            color = colors[((x+8)*3+z//4)%len(colors)]
            polygon([(x0,z),(x+8,z),(x+8,z+4),(x0,z+4)],color)
            edge([(x0,z+4),(x0,z),(x+8,z)],"#3d394d")
            edge([(x0+.22,z+3.8),(x0+.22,z+.30),(x+7.70,z+.30)],"#554c5d")
    # Narrow brass and plum inlays follow the perimeter; the chest bay stays visually still.
    for n, color in [(1,"#6b566a"),(1.35,"#605063"),(29.0,"#3b354a"),(29.4,"#846875"),
                     (30.0,"#393347"),(31,"#56475c")]:
        edge([(0,n),(36,n)],color)
    for x in (1.5,31):
        edge([(x,0),(x,50)],"#655469")
        edge([(x+.30,0),(x+.30,50)],"#39354a")
    # Inlaid compass medallion well in front of the chest bay.
    cx,cz = 19,32
    for w,h,col in [(5.3,4.6,"#665568"),(4.9,4.2,"#3b3549"),(4.6,4,"#776273"),(4.3,3.7,"#4c4155")]:
        polygon([(cx-w,cz),(cx-.66*w,cz-.66*h),(cx,cz-h),(cx+.66*w,cz-.66*h),
                 (cx+w,cz),(cx+.66*w,cz+.66*h),(cx,cz+h),(cx-.66*w,cz+.66*h)],col)
    for dx,dz in [(1,0),(0,1),(-1,0),(0,-1)]:
        polygon([(cx+dx*3.8,cz+dz*3.5),(cx-dz*.7,cz+dx*.7),(cx,cz)],"#907682")
        polygon([(cx+dx*3.8,cz+dz*3.5),(cx+dz*.7,cz-dx*.7),(cx,cz)],"#5e5269")
    return img


def frame():
    img = Image.new("RGBA",(256,256))
    d=ImageDraw.Draw(img)
    def octagon(m, cut):
        end=255-m
        return [(m+cut,m),(end-cut,m),(end,m+cut),(end,end-cut),(end-cut,end),
                (m+cut,end),(m,end-cut),(m,m+cut)]
    for margin, cut, color in [(0,11,"#1e1d2f"),(2,12,"#564452"),(3,12,"#ae827d"),
                               (4,13,"#755767"),(6,14,"#473a50"),(8,14,"#2d293e"),
                               (10,14,"#8b7084"),(11,14,"#ad95aa"),(12,14,"#5b4b67"),
                               (14,14,"#423c55"),(16,14,"#8f7889"),(17,14,"#262539")]:
        d.polygon(octagon(margin,cut),fill=color)
    aperture=Image.new("L",img.size)
    ImageDraw.Draw(aperture).polygon(octagon(19,13),fill=255)
    img.paste(room_painting(),(0,0),aperture)
    # Carved quarter flourishes, repeated as metal hardware in all four corners.
    for reflect_x in (False,True):
        for reflect_y in (False,True):
            def pt(x,y):
                return (255-x if reflect_x else x,255-y if reflect_y else y)
            def path(points,col,width=1):
                d.line([pt(x,y) for x,y in points],fill=col,width=width)
            def poly(points,col):
                d.polygon([pt(x,y) for x,y in points],fill=col)
            path([(3,67),(3,18),(18,3),(70,3)],"#e1b59f" if not reflect_y else "#a68085")
            path([(5,47),(5,20),(21,5),(46,5)],"#9c7882")
            poly([(8,39),(8,25),(17,16),(33,8),(49,8),(39,11),(28,13),(14,27)],"#3a3248")
            path([(8,37),(10,28),(25,13),(36,9),(45,9)],"#c59a91",2)
            path([(12,32),(12,27),(27,12),(33,12)],"#d8b4ad")
            path([(10,41),(10,36),(16,32),(19,26),(25,20),(32,17),(38,11),(44,11)],"#71617b")
            poly([(13,23),(17,16),(22,13),(20,18),(17,21)],"#b99aac")
            d.point(pt(17,17),fill="#f1d7cd")
            path([(14,54),(14,34),(34,14),(55,14)],"#ae99b2")
            for x in (57,76):
                path([(x-2,7),(x+1,7),(x,9),(x-3,9),(x-2,7)],"#6c5266")
                d.point(pt(x-1,7),fill="#d0a397")
            for y in (57,76,97):
                path([(7,y-2),(9,y-1),(9,y+1),(7,y+2),(6,y)],"#74596a")
                d.point(pt(7,y-1),fill="#cba4a0")
    # Mirror mounts on the top and bottom edges receive the renderer's border-only glint.
    for cy in (12,244):
        toward = 1 if cy==12 else -1
        def plate(points,col):
            d.polygon([(128+x,cy+toward*y) for x,y in points],fill=col)
        plate([(-31,-5),(-17,-5),(-12,-8),(12,-8),(17,-5),(31,-5),(20,0),
               (13,2),(7,7),(0,10),(-7,7),(-13,2),(-20,0)],"#2a263a")
        plate([(-27,-4),(-16,-4),(-10,-7),(10,-7),(16,-4),(27,-4),(17,-1),
               (11,1),(6,5),(0,8),(-6,5),(-11,1),(-17,-1)],"#ad8587")
        plate([(-23,-3),(-14,-3),(-9,-6),(9,-6),(14,-3),(23,-3),(12,0),
               (7,2),(0,7),(-7,2),(-12,0)],"#6a5066")
        plate([(-12,-1),(-7,-4),(7,-4),(12,-1),(6,3),(0,5),(-6,3)],"#d7ac9c")
        plate([(-9,0),(-6,-3),(6,-3),(9,0),(5,2),(0,4),(-5,2)],"#493e59")
        plate([(-7,0),(-5,-2),(5,-2),(7,0),(3,2),(-3,2)],"#877396")
        plate([(-7,0),(-5,-2),(5,-2),(2,0),(-3,1)],"#b79aaa")
    return img


def chest():
    # Closed lid, two rose-brass binding straps and a bright latch on a dark blue cabinet.
    s = Image.new("RGBA",(22,20))
    d=ImageDraw.Draw(s)
    d.polygon([(0,6),(2,4),(7,1),(11,1),(19,3),(21,5),(21,14),(16,19),
               (13,19),(1,16),(0,14)],fill="#211d2e")
    d.polygon([(1,7),(15,9),(15,17),(1,14)],fill="#4e586b")
    d.polygon([(16,9),(20,6),(20,14),(16,17)],fill="#354050")
    d.polygon([(1,6),(2,5),(7,2),(11,2),(18,4),(20,5),(15,9)],fill="#aaa1ae")
    d.line([(2,5),(8,2),(11,2),(17,4)],fill="#dbb9b3")
    d.line([(3,6),(14,8),(18,6)],fill="#87889a")
    d.line([(1,14),(14,17),(20,13)],fill="#987b83")
    for x,y in [(3,6),(12,8)]:
        d.line([(x,y),(x+4,y-3),(x+5,y-3)], fill="#e2b694",width=2)
        d.line([(x,y+1),(x,y+8)],fill="#a77b77",width=2)
        d.line([(x,y+1),(x,y+3)],fill="#d8a58d")
    d.line([(1,9),(14,12),(20,8)],fill="#262738",width=2)
    d.line([(2,11),(13,13)],fill="#70838b")
    d.line([(17,12),(19,10)],fill="#65727c")
    d.polygon([(6,9),(10,10),(10,13),(8,15),(6,13)],fill="#342f40")
    d.polygon([(6,9),(9,10),(9,12),(8,13),(7,13),(6,12)],fill="#d5ae8e")
    d.rectangle((7,10,8,11),fill="#b4ebe1")
    d.point((7,10),fill="#eff7e5")
    d.point((7,13),fill="#ac817b")
    return s


if __name__ == "__main__":
    atlas = Image.new("RGBA",(512,256))
    atlas.paste(frame(),(0,0))
    atlas.paste(chest(),(256,0))
    atlas.save(TEXTURE,optimize=True)
