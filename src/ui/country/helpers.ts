import type { CountryBundle } from "@engine/countryGame";
import type { PartyId } from "@engine/system";

// Bundle-scoped party display helpers (the generic analog of ui/uk/parties.ts).
export function partyColor(country: CountryBundle, id: PartyId): string {
  return country.system.parties.find((p) => p.id === id)?.color ?? "#888";
}

// Country colors also paint solid map regions. Dark map blues need a lighter
// text variant on the setup cards and summary, where the surface is navy.
export function partyTextColor(country: CountryBundle, id: PartyId): string {
  const color = partyColor(country, id);
  if (!/^#[0-9a-f]{6}$/i.test(color)) return color;
  const rgb = [1, 3, 5].map((at) => parseInt(color.slice(at, at + 2), 16));
  const channel = (value: number) => {
    const srgb = value / 255;
    return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4;
  };
  const luminance = (value: number[]) =>
    0.2126 * channel(value[0]) + 0.7152 * channel(value[1]) + 0.0722 * channel(value[2]);
  const surfaceLuminance = luminance([26, 39, 51]);
  while ((luminance(rgb) + 0.05) / (surfaceLuminance + 0.05) < 4.5) {
    for (let i = 0; i < rgb.length; i++) rgb[i] = Math.round(rgb[i] + (255 - rgb[i]) * 0.08);
  }
  return `#${rgb.map((value) => value.toString(16).padStart(2, "0")).join("")}`;
}
export function partyShort(country: CountryBundle, id: PartyId): string {
  return country.system.parties.find((p) => p.id === id)?.shortName ?? id.toUpperCase();
}
export function partyName(country: CountryBundle, id: PartyId): string {
  return country.system.parties.find((p) => p.id === id)?.name ?? id;
}

export function byDisplayOrder(country: CountryBundle, a: PartyId, b: PartyId): number {
  const order = country.system.parties.map((p) => p.id);
  const ia = order.indexOf(a), ib = order.indexOf(b);
  return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
}

export function sortBySeats(country: CountryBundle, seats: Record<PartyId, number>): PartyId[] {
  return Object.keys(seats)
    .filter((p) => (seats[p] ?? 0) > 0)
    .sort((a, b) => (seats[b] - seats[a]) || byDisplayOrder(country, a, b));
}
