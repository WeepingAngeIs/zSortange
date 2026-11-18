"""Author the 16px Anya control atlas. Pixel geometry is original and stays on the integer grid."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "src/main/resources/assets/anyastorage/textures/gui"
INK = "#19162f"
SHADE = "#493956"
ALLOY = "#a08bb9"
LIGHT = "#eee4fa"
ROSE = "#ed92b4"
PLUM = "#a55f88"
MINT = "#9ee9dc"
TEAL = "#487f88"
AMBER = "#ffce94"


def sprite(shape, badge=""):
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)
    def poly(vertices, fill=ALLOY, border=INK):
        d.polygon(vertices, fill=fill, outline=border)
    def line(points, color, w=1):
        d.line(points, fill=color, width=w)
    def rect(box, color):
        d.rectangle(box, fill=color)
    def gem(x, y, r=3, color=MINT):
        poly([(x,y-r),(x+r,y),(x,y+r),(x-r,y)],color)
        d.point((x,y-r+1),fill=LIGHT)
        line([(x+1,y),(x,y+r-1)],TEAL)
    def case(box=(2,4,13,12)):
        x,y,r,b=box
        poly([(x+2,y),(r-2,y),(r,y+2),(r,b-2),(r-2,b),(x+2,b),(x,b-2),(x,y+2)],SHADE)
        line([(x+1,y+3),(x+3,y+1),(r-3,y+1)],ALLOY)
        line([(x+3,b-1),(r-3,b-1),(r-1,b-3)],PLUM)
    def crystal(x,y):
        poly([(x,y-4),(x+3,y-2),(x+2,y+2),(x-1,y+4),(x-3,y+1),(x-2,y-3)],ROSE)
        line([(x-2,y),(x-1,y-2),(x,y-3)],LIGHT)
        line([(x,y+2),(x+1,y-1)],PLUM)
    def book(opened=False):
        if opened:
            poly([(1,3),(5,3),(8,5),(11,3),(14,3),(14,12),(11,12),(8,14),(5,12),(1,12)],PLUM)
            line([(2,4),(5,4),(7,6),(7,11)],LIGHT);line([(9,11),(9,6),(11,5),(13,5)],MINT)
        else:
            poly([(3,2),(11,2),(13,4),(13,13),(11,14),(3,14),(2,13),(2,4)],SHADE)
            line([(4,3),(4,12),(11,12)],ALLOY);line([(5,4),(10,4)],LIGHT)
            line([(6,7),(10,7)],MINT);line([(6,9),(9,9)],TEAL)
    def person():
        poly([(5,2),(8,2),(10,4),(9,7),(7,8),(5,7),(4,5)],ALLOY)
        line([(5,4),(6,3),(8,3)],LIGHT);line([(6,6),(8,6)],MINT)
        poly([(5,8),(8,9),(10,8),(13,11),(13,13),(3,13),(2,12),(3,10)],PLUM)
        line([(4,10),(5,9),(7,11),(9,9)],ROSE);line([(4,12),(10,12)],ALLOY)
    def port():
        poly([(2,14),(2,6),(6,2),(10,2),(13,5),(13,13),(11,14),(11,7),(9,5),(6,5),(4,7),(4,14)],ALLOY)
        line([(3,9),(3,6),(6,3),(9,3)],LIGHT);line([(12,7),(12,11)],ROSE)
    def star(x=8,y=7):
        poly([(x,y-6),(x+2,y-2),(x+6,y-1),(x+3,y+2),(x+3,y+6),(x,y+4),(x-4,y+6),(x-3,y+2),(x-6,y-1),(x-2,y-2)],AMBER)
        line([(x-3,y),(x-1,y-1),(x,y-3)],LIGHT);line([(x+1,y+2),(x+3,y)],ROSE)
    if shape=="eye":
        poly([(0,8),(4,4),(7,3),(10,3),(15,7),(11,11),(8,12),(5,12)],ALLOY)
        poly([(2,8),(5,5),(9,4),(13,7),(10,10),(6,11)],TEAL)
        gem(8,7,3,MINT);d.point((8,7),fill=INK);d.point((7,6),fill=LIGHT)
        line([(3,6),(5,4),(9,3)],LIGHT)
    elif shape in ("receive","extract"):
        poly([(1,8),(4,8),(5,11),(10,11),(11,8),(14,8),(13,13),(11,14),(4,14),(2,12)],ALLOY)
        line([(2,9),(3,11),(5,12),(10,12)],LIGHT)
        if shape=="receive":
            poly([(6,1),(9,1),(9,5),(12,5),(8,10),(3,5),(6,5)],MINT);line([(7,2),(7,6),(5,6)],LIGHT)
        else:
            poly([(7,0),(12,5),(9,5),(9,9),(6,9),(6,5),(3,5)],ROSE);line([(5,4),(7,2),(9,4)],LIGHT)
    elif shape=="ledger":
        book();poly([(6,12),(7,8),(12,3),(14,5),(9,10)],ROSE);line([(8,8),(12,4)],LIGHT)
    elif shape=="gate":
        port();gem(8,9,3,MINT);rect((5,14,11,14),TEAL)
    elif shape=="build":
        poly([(1,13),(1,9),(5,9),(5,6),(9,6),(9,13)],ALLOY)
        line([(2,10),(5,10)],LIGHT);line([(6,7),(8,7)],MINT)
        poly([(5,13),(10,5),(9,3),(11,1),(15,5),(13,7),(12,6),(7,14)],ROSE)
        line([(10,3),(11,2),(13,4)],LIGHT)
    elif shape in ("crew","person"):
        if shape=="crew":
            gem(12,5,3,ROSE);poly([(10,9),(13,8),(15,10),(15,13),(11,13)],ALLOY)
        person()
    elif shape=="back":
        poly([(1,7),(6,2),(6,5),(11,5),(14,8),(14,12),(12,14),(10,14),(11,11),(10,9),(6,9),(6,12)],ALLOY)
        line([(3,7),(5,5)],LIGHT);line([(7,6),(10,6),(12,8)],MINT)
    elif shape in ("prev","next"):
        # A folded page corner beside the book's spine.
        poly([(2,7),(9,1),(10,4),(6,7),(10,11),(9,14)],ALLOY)
        line([(3,7),(9,2)],LIGHT);line([(5,8),(9,12)],ROSE)
        line([(12,3),(12,11)],TEAL,2);d.point((12,2),MINT)
        if shape=="next":im=im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    elif shape in ("up","down"):
        # Lift inside a two-rail scroll track.
        line([(1,3),(1,12)],ALLOY);line([(14,3),(14,12)],ALLOY)
        poly([(3,8),(8,2),(13,8),(10,8),(10,12),(6,12),(6,8)],TEAL)
        line([(5,7),(8,4),(10,6)],LIGHT);line([(7,10),(9,10)],MINT)
        if shape=="down":im=im.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    elif shape=="close":
        poly([(1,4),(4,1),(8,5),(11,2),(14,5),(10,8),(14,12),(11,15),(7,11),(4,14),(1,11),(5,7)],PLUM)
        line([(3,4),(4,3),(11,11)],ROSE);line([(4,11),(11,4)],LIGHT)
    elif shape=="check":
        poly([(1,8),(4,5),(7,8),(12,2),(15,4),(8,14),(6,14)],TEAL)
        line([(3,8),(6,11),(7,11),(13,4)],MINT);d.point((13,3),LIGHT)
    elif shape=="stack":
        for x,y in [(3,9),(3,5),(3,1)]:
            poly([(x,y+2),(x+5,y),(x+10,y+2),(x+10,y+4),(x+5,y+6),(x,y+4)],ALLOY)
            line([(x+1,y+2),(x+5,y+1),(x+9,y+2)],LIGHT)
            line([(x+6,y+4),(x+9,y+3)],TEAL)
    elif shape=="dump":
        poly([(1,3),(8,1),(12,5),(8,9),(4,9)],ALLOY);line([(3,4),(7,3),(9,4)],LIGHT)
        line([(10,7),(12,9),(12,10)],MINT,2);rect((9,11,10,12),ROSE);rect((13,12,14,13),MINT)
        line([(5,13),(6,14),(12,14)],ALLOY)
    elif shape=="organize":
        for x,y in [(2,3),(8,3),(2,9)]:
            poly([(x,y),(x+4,y),(x+4,y+4),(x,y+4)],ALLOY);rect((x+1,y+1,x+2,y+2),LIGHT)
        gem(10,11,3,MINT)
    elif shape=="lock":
        line([(4,8),(4,4),(6,2),(9,2),(11,4),(11,8)],INK,3)
        line([(4,7),(4,4),(6,2),(9,2),(11,4),(11,6)],ALLOY)
        case((2,6,13,14));gem(8,10,2,AMBER);d.point((8,12),AMBER)
    elif shape=="shield":
        poly([(2,3),(7,1),(13,3),(13,8),(11,11),(7,15),(3,11),(2,8)],ALLOY)
        poly([(4,5),(8,3),(11,5),(11,9),(7,12),(4,9)],TEAL)
        line([(5,6),(7,5),(8,5)],LIGHT)
    elif shape=="chip":
        for k in (4,7,10):
            rect((k,1,k,14),ALLOY);rect((1,k,14,k),ALLOY)
        case((3,3,12,12));gem(7,7,3,ROSE)
    elif shape=="info":
        case((2,1,13,14));gem(7,5,2,MINT);line([(7,8),(8,8),(8,11)],LIGHT);rect((6,12,10,12),ALLOY)
    elif shape=="bag":
        line([(5,4),(5,3),(7,1),(9,1),(11,3),(11,4)],ALLOY)
        case((2,4,13,14));poly([(3,5),(12,5),(10,9),(5,9)],PLUM);gem(8,8,2,AMBER);line([(5,12),(9,12)],LIGHT)
    elif shape=="hub":
        case((1,7,14,13));poly([(2,6),(5,2),(10,2),(13,6),(11,9),(4,9)],ALLOY)
        line([(4,5),(6,3),(9,3)],LIGHT);gem(8,7,3,MINT);rect((3,11,5,11),ROSE)
    elif shape=="star":star()
    elif shape=="label":
        poly([(1,7),(7,1),(13,1),(14,7),(7,14)],PLUM);line([(3,7),(8,2),(12,2)],ROSE)
        gem(10,4,2,LIGHT);line([(5,8),(8,11)],LIGHT)
    elif shape=="target":
        for box,co in [((2,2,13,13),ALLOY),((4,4,11,11),TEAL)]:
            d.ellipse(box,outline=co)
        gem(8,7,2,ROSE);line([(8,0),(8,3)],LIGHT);line([(13,7),(15,7)],LIGHT)
    elif shape=="ore":crystal(6,7);crystal(10,10);rect((3,13,8,13),TEAL)
    elif shape=="recipe":
        book(True);rect((3,6,4,7),AMBER);rect((5,8,6,9),ROSE);rect((10,7,11,8),MINT)
    elif shape=="settings":
        for x,y in [(3,4),(8,9),(12,5)]:
            line([(x,2),(x,13)],ALLOY);rect((x-1,y,x+1,y+2),INK);rect((x-1,y,x+1,y+1),MINT if x==8 else ROSE)
    elif shape=="pulse":
        case((0,2,15,12));line([(2,8),(4,8),(6,4),(8,10),(10,6),(13,6)],MINT);rect((4,14,11,14),ALLOY)
    elif shape=="index":book();rect((11,3,14,5),ROSE);rect((11,7,14,9),MINT);rect((11,11,13,12),AMBER)
    elif shape=="image":
        case((1,2,14,13));poly([(3,10),(6,6),(8,9),(10,7),(12,10)],MINT);rect((10,4,11,5),AMBER)
    elif shape=="radius":
        d.arc((1,1,14,14),200,85,ALLOY,2);d.arc((4,4,11,11),195,60,MINT)
        line([(8,7),(12,3)],ROSE);gem(7,8,2,MINT)
    elif shape=="slot":
        case((1,2,14,13));rect((4,5,10,9),INK);line([(4,9),(10,9),(11,8)],MINT);line([(4,4),(8,4)],LIGHT)
    elif shape=="route":
        line([(3,12),(3,8),(8,8),(8,4),(13,4)],ALLOY,2);line([(8,8),(12,12)],PLUM,2)
        for x,y in [(3,12),(13,3),(12,12)]:gem(x,y,2,MINT if x==3 else ROSE)
    elif shape=="leaf":
        poly([(2,12),(3,6),(6,3),(14,1),(13,8),(10,11),(5,13)],TEAL)
        line([(2,14),(11,5)],MINT);line([(5,8),(7,9),(10,9)],LIGHT)
    elif shape=="cube":
        poly([(1,5),(7,1),(14,4),(14,11),(8,15),(1,12)],ALLOY)
        poly([(2,5),(7,2),(13,4),(8,7)],LIGHT);poly([(8,8),(13,5),(13,10),(8,13)],TEAL)
        line([(3,7),(6,9)],PLUM)
    elif shape=="pick":
        line([(3,13),(10,5)],INK,4);line([(3,13),(10,5)],ROSE,2)
        poly([(3,3),(7,2),(11,4),(14,9),(14,11),(11,8),(9,6),(5,4)],ALLOY);line([(5,3),(7,3),(10,4)],LIGHT)
    elif shape=="filter":
        poly([(1,2),(14,2),(14,5),(9,10),(9,13),(6,15),(6,10),(1,5)],ALLOY)
        line([(3,3),(12,3)],LIGHT);line([(4,6),(11,6)],ROSE);rect((7,10,8,12),TEAL)
    elif shape=="flame":
        poly([(2,10),(4,6),(6,6),(6,1),(9,4),(11,7),(13,5),(14,10),(11,14),(6,15)],PLUM)
        poly([(5,11),(7,8),(8,5),(11,10),(9,13),(7,13)],AMBER);d.point((8,10),LIGHT)
    elif shape=="heart":
        poly([(1,4),(4,2),(7,4),(8,6),(10,3),(13,3),(15,6),(14,9),(8,15),(2,9)],PLUM)
        line([(2,6),(3,4),(5,4)],LIGHT);line([(4,9),(8,12),(12,8)],ROSE)
    elif shape=="craft":
        case((2,2,13,13))
        for x in (4,8):
            for y in (4,8):rect((x,y,x+2,y+2),MINT if x==y else ALLOY)
    elif shape=="compact":
        poly([(2,4),(6,4),(6,2),(10,2),(10,4),(14,4),(12,7),(4,7)],ALLOY)
        gem(8,9,2,ROSE);rect((2,12,13,14),ALLOY);line([(3,12),(12,12)],LIGHT)
    elif shape=="wardrobe":
        line([(7,5),(7,3),(9,2),(10,3),(10,4),(8,6),(8,7),(2,11),(2,12),(13,12),(14,11),(9,8)],ALLOY,2)
        line([(3,11),(7,8)],LIGHT);gem(8,11,2,ROSE)
    elif shape=="layers":
        for y,c in [(9,TEAL),(6,PLUM),(3,ALLOY)]:poly([(1,y+2),(7,y-1),(14,y+1),(8,y+5)],c)
        line([(3,5),(7,3),(11,4)],LIGHT)
    elif shape=="key":
        poly([(2,4),(5,1),(8,2),(10,5),(9,8),(7,9),(4,8),(2,7)],ALLOY)
        gem(6,5,2,TEAL);line([(8,8),(13,13),(14,12)],ROSE,2);line([(11,11),(12,9)],AMBER)
    elif shape=="bin":
        case((3,5,12,14));line([(2,4),(13,4)],ALLOY,2);rect((6,2,9,3),ROSE);line([(6,8),(6,11)],LIGHT);line([(9,8),(9,11)],ROSE)
    elif shape=="spark":
        gem(6,6,5,ROSE);gem(12,11,3,MINT);rect((2,12,3,13),AMBER)
    elif shape=="model":
        poly([(2,5),(8,2),(13,5),(13,11),(8,14),(2,11)],SHADE)
        line([(2,5),(8,8),(13,5),(8,2),(2,5),(2,11),(8,14),(8,8)],MINT);line([(13,6),(13,11),(8,14)],ROSE)
    elif shape=="clock":
        d.ellipse((1,2,14,14),fill=SHADE,outline=ALLOY,width=2);line([(8,4),(8,8),(11,9)],MINT);rect((6,0,9,1),ROSE);d.point((4,4),LIGHT)
    elif shape=="book":book(True)
    elif shape=="scroll":book()
    elif shape=="diamond":gem(8,8,7);line([(4,8),(6,5),(9,3)],LIGHT)
    elif shape=="link":
        d.rounded_rectangle((1,6,9,12),2,outline=ALLOY,width=2)
        d.rounded_rectangle((7,2,14,9),2,outline=ROSE,width=2);line([(6,9),(10,5)],LIGHT)
    elif shape=="bottle":
        rect((5,1,10,2),ALLOY);poly([(5,3),(10,3),(10,6),(13,10),(11,14),(4,14),(2,11),(5,6)],SHADE)
        poly([(4,10),(6,8),(10,8),(12,11),(10,13),(5,13)],MINT);line([(6,4),(6,6)],LIGHT)
    elif shape=="moon":
        poly([(10,1),(7,4),(7,8),(10,11),(14,11),(12,14),(6,14),(2,11),(1,7),(3,3),(6,1)],ALLOY)
        line([(3,7),(4,4),(7,2)],LIGHT);gem(12,5,2,ROSE)
    elif shape=="ripple":
        poly([(8,1),(12,7),(13,10),(11,13),(7,14),(3,12),(2,9),(4,6)],TEAL)
        line([(4,9),(6,6),(7,4)],MINT);line([(6,11),(8,12),(10,11)],LIGHT)
    elif shape=="ghost":
        poly([(2,13),(2,7),(4,3),(7,1),(10,2),(13,5),(14,13),(10,11),(8,14),(6,11)],ALLOY)
        rect((5,6,6,8),INK);rect((9,6,10,8),INK);line([(4,4),(6,3)],LIGHT)
    elif shape=="cat":
        poly([(2,3),(6,5),(9,5),(13,2),(13,10),(10,13),(5,13),(2,10)],ALLOY)
        line([(4,9),(6,9)],MINT);line([(9,9),(11,8)],MINT);d.point((7,11),ROSE)
    elif shape=="barrel":
        case((3,2,12,14));line([(4,6),(11,6)],MINT);line([(4,10),(11,10)],ROSE);line([(7,3),(7,5)],LIGHT)
    elif shape=="mask":
        poly([(1,4),(5,5),(8,3),(11,5),(14,3),(13,9),(9,14),(6,14),(2,10)],PLUM)
        line([(3,7),(6,8)],LIGHT);line([(9,8),(12,6)],MINT)
    elif shape=="swatch":
        poly([(2,8),(8,1),(14,6),(8,14),(4,13)],PLUM);line([(4,8),(9,3),(12,5)],ROSE)
        line([(5,11),(10,5)],LIGHT);d.point((6,12),MINT)
    else: raise ValueError(shape)
    # One pixel of deep ink holds pale inside edges together on any interior palette.
    alpha=im.getchannel("A")
    outlined=Image.new("RGBA",im.size,INK); outlined.putalpha(alpha.filter(ImageFilter.MaxFilter(3)))
    outlined.alpha_composite(im);im=outlined
    if badge:
        d=ImageDraw.Draw(im)
        patterns={
            "plus":[".x.","xxx",".x."],"minus":["...","xxx","..."],"check":["..x","x.x",".x."],
            "ring":[".x.","x.x",".x."],"dot":["...",".x.","..."],"bars":["x..","xx.","xxx"],
            "fork":["x.x",".x.",".x."],"star":["..x",".x.","x.x"],"up":[".x.","xxx","x.x"],
            "down":["x.x","xxx",".x."],"lock":[".xx.","x..x","xxxx","x.xx"],
            "wave":["....","x..x",".xx."],"clock":[".xx.","xxx.",".xx."],
            "sheet":["xxx","x.x","xxx"],"pair":["xx..","..xx","xx.."],"bolt":[".xx","xx.",".x."],
            "tag":["xx.","x.x",".x."],"cross":["x.x",".x.","x.x"],"leaf":["..x",".xx","xx."],
            "shield":["xxx","xxx",".x."],"back":[".x.","xx.",".xx"],"key":["xx.","xx.","..x"],"gate":[".x.","x.x","x.x"],"limit":["xxx","..x","xxx"],"mark":[".x.",".x.","...",".x."],
        }
        pattern=patterns[badge]; d.rounded_rectangle((9,9,15,15),radius=1,fill=INK,outline=PLUM)
        for yy,row in enumerate(pattern):
            for xx,v in enumerate(row):
                if v=="x":d.point((11+xx,11+yy),fill=AMBER if badge in ("minus","cross","mark") else MINT)
    return im

# A shared motif and its small inscription form one action. A different operation gets a different seal.
CATALOG = """
VIEW eye
DEPOSIT receive
WITHDRAW extract
ACTIVITY ledger
VISIT gate
BUILD build
MEMBER person
MEMBERS crew
ADD_MEMBER person plus
REMOVE_MEMBER person minus
BACK back
PREVIOUS prev
NEXT next
SCROLL_UP up
SCROLL_DOWN down
CLOSE close
ORGANIZE organize
QUICKSTACK stack
DUMP dump
PROTECT lock
UPGRADES chip
FAVORITES star
INFO info
BASE hub
SYMBOL label
EXIT gate fork
CANCEL back cross
CONFIRM check
TIERS bag bars
LIMITS radius limit
ORE_VALUES ore bars
RECIPES recipe
UPGRADE_SWITCH chip fork
PRESETS settings pair
DIAGNOSTICS pulse
CATEGORY_RULES index fork
POLICY shield sheet
MODERATION image shield
DISCARD close cross
REVIEW eye sheet
TIER bag
ADD_TIER bag plus
CAPACITY slot
UPGRADE_CAPACITY chip plus
RECIPE_CELLS craft bars
PREDECESSOR layers up
MATERIAL ore
NAME label dot
RECIPE_PREVIEW recipe ring
RECIPE_STATUS recipe mark
ORE_ADD ore plus
CUBE_RECIPE cube star
SURVIVAL leaf
LARGE_NETWORK route plus
CHANGE ledger dot
IMPACT pulse ring
APPLY check sheet
HOTBAR lock bars
EXPLAIN info fork
BASE_NAME hub tag
FEED_ORE ore down
RADIUS radius
ROUTING route
PERFORMANCE clock
TRUSTED crew check
BASE_LOG ledger clock
LOG_LINE scroll dot
LOG_PRIVACY ledger lock
CLEAR_LOG ledger cross
CONTAINER cube
CATEGORY index
PRIORITY layers bars
OVERFLOW receive wave
FILTER filter
CLEAR_FILTER filter cross
ROUTE_ORDER route bars
WARDROBE wardrobe
UPGRADE_LIST chip bars
PICKUP_FILTER filter down
SMELT_FILTER flame
FEEDING heart
CRAFT craft
COMPACT compact
RESUPPLY pick plus
EDIT_PICKUP filter plus
EDIT_SMELT flame plus
STOP_SMELT flame cross
CONFIRM_FEED ore check
HELD_RULE index plus
TYPE_RULE index tag
SETTINGS settings
PERMISSIONS key
SAFETY shield
PROGRESSION layers
WEAR wardrobe check
PERSONAL_IMAGES image lock
INTERIOR swatch
IMAGE_PRIVACY shield ring
IMAGE_VIEWER image plus
BUILTIN_INTERIOR swatch back
APPROVE_IMAGE image check
DELETE_IMAGE image cross
IMAGE image
PARTICLES spark
MODELS model
CONTENTS book
READ book ring
CHAPTER scroll
SLOT_PREF slot lock
PICKUP receive plus
REFILL bottle
SMELTING flame clock
COMPAT link
FALLBACK link fork
BACKPACKS bag check
NETWORK route sheet
LABELS label sheet
AUDIT ledger check
VANILLA_CHESTS cube lock
VANILLA_BARRELS barrel
VANILLA_SHULKERS cube up
HUBS hub check
TERMINALS key bolt
CONTAINERS cube check
ENDGAME star lock
INITIAL_RADIUS radius dot
MAX_RADIUS radius ring
RADIUS_STEP radius plus
QUICK_RADIUS radius bolt
SCAN_CHUNKS pulse clock
MAX_CONTAINERS cube limit
MAX_ENTRIES ledger limit
OPERATION_SLOTS slot limit
UPGRADE_COST ore limit
TUTORIAL_GRANT book plus
TUTORIAL_RECOVERY book back
LOGGING ledger bolt
ACTOR_IDS person tag
MEMBER_LOGS crew sheet
LOG_ENTRIES scroll limit
ELEMENTAL spark star
WEARABLE_MODELS model check
COSMETIC_PARTICLES spark ring
STONE_ARMOR shield bars
DIAMOND_ARMOR diamond
MAGMA flame shield
SWIM ripple
KNOCKBACK shield fork
MONSTER_BAGS mask
MONSTER_CHANCE mask ring
MONSTER_DROP mask down
MONSTER_SPECIES mask sheet
IMAGES_ENABLED image bolt
IMAGE_UPLOAD image up
IMAGE_SHARING image fork
IMAGE_MODERATION image key
UPLOADERS person up
IMAGES_PER_PLAYER person limit
IMAGES_TOTAL image limit
IMAGE_SIZE image bars
POCKET_CORE gate star
SESSION gate clock
POCKET_EDIT build plus
POCKET_STORAGE gate check
POCKET_LOG gate sheet
POCKET_NAMES gate tag
POCKET_LIMIT gate limit
ACTIVE_POCKETS gate bolt
POCKET_CONTAINERS gate bars
POCKET_ENTRIES ledger gate
ELEMENT_PLAIN swatch dot
ELEMENT_LEAF leaf star
ELEMENT_STONE shield dot
ELEMENT_DIAMOND diamond star
ELEMENT_MAGMA flame star
ELEMENT_WATER ripple star
SKIN_ELEMENTAL spark sheet
SKIN_BOW_CAT cat plus
SKIN_MIDNIGHT_CAT cat
SKIN_SPOOKY ghost
SKIN_CUTE_SPOOKY ghost star
SKIN_FUTURISTIC chip bolt
SKIN_GALAXY moon
SKIN_OAK leaf sheet
SKIN_CHERRY leaf plus
SKIN_DARK_OAK leaf lock
"""
CAT = {row.split()[0]: row.split()[1:] for row in CATALOG.strip().splitlines()}
ASSET = DEST / "control_atlas.png"

def atlas():
    sheet = Image.new("RGBA", (256, 256))
    tiles = {}
    for i, (name, recipe) in enumerate(CAT.items()):
        tile = sprite(*recipe)
        if tile.tobytes() in tiles: raise ValueError(f"Repeated icon for {name} and {tiles[tile.tobytes()]}")
        tiles[tile.tobytes()] = name
        sheet.paste(tile, ((i % 16) * 16, (i // 16) * 16))
    DEST.mkdir(exist_ok=True, parents=True)
    sheet.save(ASSET, optimize=True)

if __name__ == "__main__":
    atlas()
