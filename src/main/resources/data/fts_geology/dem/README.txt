Real mountain ground
====================

The .dem files beside this one are crops of real ranges, taken from the DEM3 elevation data published by
Jonathan de Ferranti at viewfinderpanoramas.org, which is itself built on NASA's SRTM and on other public
sources. The site allows reproduction with acknowledgement and a link:

    http://viewfinderpanoramas.org/dem3.html

Each crop is 43 by 29 km of ground, resampled to a 90 m grid with the range's trend along its long side, and
stored as metres above sea level with a fixed amount taken off (see the header). The mod lays a mountain belt
out from them at true scale: twenty-five metres to the block in the normal world, ten in the tall one.

    alps_*      the Alps: Oetztal, Bernina, Valais, Bernese Oberland, Mont Blanc
    cauc_*      the Caucasus: Bezengi, Svaneti, Kazbek
    him_*       the Himalaya: Manaslu, Langtang, Cho Oyu
    kara_*      the Karakoram: Baltoro, Nanga Parbat
    app_*       the Appalachians: Valley and Ridge, Blue Ridge, New River
    land_*      the named mountains, each laid down once in the tall world: Everest, K2, the Matterhorn

Format: int magic "FTDD", short version, short width, short height, short metres per pixel, short shift,
then a deflated stream of big-endian shorts, each row given as its first value and then deltas.
