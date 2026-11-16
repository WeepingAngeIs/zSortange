"""Extend the pinned binary audit to the separate point-of-interest persistence path."""
from pathlib import Path
source=Path('scripts/durable_api_audit.py').read_text()
assert source.count('classes=[')==1
source=source.replace('classes=[',"classes=['net.minecraft.world.entity.ai.village.poi.PoiTypes','net.minecraft.world.entity.ai.village.poi.PoiManager','net.minecraft.world.level.chunk.storage.SectionStorage','net.minecraft.world.level.chunk.storage.SimpleRegionStorage','net.minecraft.world.level.chunk.storage.RegionStorageInfo',")
exec(compile(source,'scripts/durable_api_audit.py','exec'))
