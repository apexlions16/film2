import Ajv from "ajv";
import titleSchema from "./title.schema.json" with { type: "json" };

const ajv = new Ajv({ allErrors: true });
const validateTitleFn = ajv.compile(titleSchema);

/**
 * catalog/titles/*.json dosyalarını doğrular.
 * @param {object} title
 * @returns {{ valid: boolean, errors: import("ajv").ErrorObject[] | null }}
 */
export function validateTitle(title) {
  const valid = validateTitleFn(title);
  return { valid, errors: validateTitleFn.errors ?? null };
}

export { titleSchema };
