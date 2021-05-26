/**
mapbox static image for recyclerview
**/

val zoom = getZoomLevelForSmallMapNew(item.startRadiusMeters?.toDouble()
                        ?: 0.0)

                var styleId = StaticMapCriteria.SATELLITE_STREETS_STYLE
                if (LohiApplication.getMapBoxMapType() == Style.MAPBOX_STREETS) {
                    styleId = StaticMapCriteria.STREET_STYLE
                }
                val staticImage = MapboxStaticMap.builder()
                        .accessToken(LohiApplication.getResourceString(R.string.mapbox_access_token))
                        .styleId(styleId)
                        .cameraPoint(Point.fromLngLat(item.startLongitude!!, item.startLatitude!!)) // Image's center point on map
                        .cameraZoom(zoom)
                        .width(dpToPx(176)) // Image width
                        .height(dpToPx(176)) // Image height
                        .staticMarkerAnnotations(markers)
                        .retina(false)
                        .build()


                val imageUrl = staticImage.url().toString()
