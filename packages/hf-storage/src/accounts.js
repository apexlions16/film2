import { whoAmI } from "@huggingface/hub";

/**
 * Verilen Hugging Face token'inin ait oldugu hesabin kullanici adini (namespace)
 * dogrular ve dondurur. Studio'nun "Hugging Face hesabi ekle" akisinda kullanilir:
 * kullanici token'i yapistirir, biz bu fonksiyonla hangi hesap oldugunu tespit edip
 * gosteririz — boylece namespace'i elle yazmasi gerekmez, yanlis girilemez.
 *
 * @param {string} token
 * @returns {Promise<{ namespace: string, fullname?: string }>}
 */
export async function resolveHfAccount(token) {
  const info = await whoAmI({ accessToken: token });
  if (!info?.name) {
    throw new Error("Hugging Face token gecersiz gorunuyor (kullanici adi alinamadi).");
  }
  return { namespace: info.name, fullname: info.fullname };
}
