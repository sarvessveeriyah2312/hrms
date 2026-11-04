/** The same keys as the English messages, with any text. Malay and Chinese must fill every key. */
export type Shape<T> = { [K in keyof T]: T[K] extends string ? string : Shape<T[K]> };
