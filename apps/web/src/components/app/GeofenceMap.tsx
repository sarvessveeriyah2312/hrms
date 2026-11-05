import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

/**
 * A map for placing a site's geofence: the pin is the centre, the circle the radius. Click the map or drag the pin
 * to move it. Tiles come from OpenStreetMap (attribution required by their licence).
 */
export function GeofenceMap({ lat, lng, radiusM, onMove, label }: {
  lat: number | null; lng: number | null; radiusM: number; onMove: (lat: number, lng: number) => void; label: string;
}) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const pin = useRef<L.Marker | null>(null);
  const circle = useRef<L.Circle | null>(null);
  // The latest onMove, for map events registered once.
  const moveRef = useRef(onMove);
  useEffect(() => {
    moveRef.current = onMove;
  }, [onMove]);

  // Create the map once.
  useEffect(() => {
    if (!el.current) return;
    const m = L.map(el.current, { zoomControl: true, attributionControl: true, scrollWheelZoom: false })
      // Kuala Lumpur until the site has a location.
      .setView([3.139, 101.6869], 11);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(m);
    m.on('click', (e: L.LeafletMouseEvent) => moveRef.current(round(e.latlng.lat), round(e.latlng.lng)));
    // Scroll-zoom only after the map is clicked, so the page still scrolls past it.
    m.on('focus', () => m.scrollWheelZoom.enable());
    m.on('blur', () => m.scrollWheelZoom.disable());
    map.current = m;
    // The map may be laid out after first paint (tabs, panels): measure again.
    const t = setTimeout(() => m.invalidateSize(), 50);
    return () => {
      clearTimeout(t);
      m.remove();
      map.current = null;
      pin.current = null;
      circle.current = null;
    };
  }, []);

  // Keep the pin and circle in step with the form.
  useEffect(() => {
    const m = map.current;
    if (!m) return;
    if (lat === null || lng === null) {
      pin.current?.remove();
      circle.current?.remove();
      pin.current = null;
      circle.current = null;
      return;
    }
    const at = L.latLng(lat, lng);
    if (!pin.current) {
      pin.current = L.marker(at, {
        draggable: true,
        keyboard: true,
        title: label,
        icon: L.divIcon({ className: 'geo-pin', html: '<span></span>', iconSize: [28, 36], iconAnchor: [14, 34] }),
      }).addTo(m);
      pin.current.on('dragend', () => {
        const p = pin.current!.getLatLng();
        moveRef.current(round(p.lat), round(p.lng));
      });
      circle.current = L.circle(at, { radius: radiusM, className: 'geo-fence' }).addTo(m);
      m.fitBounds(circle.current.getBounds(), { padding: [40, 40], maxZoom: 17 });
    } else {
      pin.current.setLatLng(at);
      circle.current!.setLatLng(at).setRadius(radiusM);
      // Follow the pin when it leaves the view (e.g. after an address search).
      if (!m.getBounds().contains(at)) m.fitBounds(circle.current!.getBounds(), { padding: [40, 40], maxZoom: 17 });
    }
  }, [lat, lng, radiusM, label]);

  return <div ref={el} className="geo-map" role="application" aria-label={label} />;
}

/** Six decimals is about 10 cm: plenty for a geofence, and what the database stores. */
function round(v: number) {
  return Math.round(v * 1e6) / 1e6;
}
