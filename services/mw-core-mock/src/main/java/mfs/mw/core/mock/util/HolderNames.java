package mfs.mw.core.mock.util;

import java.util.List;

/**
 * Display names of the wallets {@code WalletSeeder} generates: realistic Bangladeshi names, fixed
 * per type and index (1-based, the last four digits of the MSISDN), so every run shows the same
 * holder for the same number. Customers are people (odd index a man, even a woman), agents shops,
 * merchants businesses, orgs companies, distributors trading houses (ticket 94). Unique for the first 300 customers and for every agent,
 * merchant and org.
 */
public final class HolderNames {

  private static final List<String> MALE_FIRST = List.of(
      "Rahim", "Kamal", "Karim", "Jamal", "Tanvir", "Rafiq", "Shakil", "Habib", "Arif", "Mizanur",
      "Faruk", "Imran", "Masud", "Sohel", "Anwar");
  private static final List<String> MALE_LAST = List.of(
      "Uddin", "Hossain", "Rahman", "Ahmed", "Hasan", "Chowdhury", "Islam", "Mahmud", "Kabir", "Miah");
  private static final List<String> FEMALE_FIRST = List.of(
      "Ayesha", "Nusrat", "Fatema", "Sumaiya", "Farhana", "Taslima", "Nasrin", "Rokeya", "Sharmin", "Sadia",
      "Shirin", "Ruma", "Laila", "Parvin", "Jannat");
  private static final List<String> FEMALE_LAST = List.of(
      "Siddiqua", "Akter", "Begum", "Khatun", "Sultana", "Jahan", "Islam", "Rahman", "Chowdhury", "Haque");
  private static final List<String> SHOP_OWNER = List.of(
      "Hossain", "Rahman", "Karim", "Islam", "Ahmed", "Chowdhury", "Miah", "Sarkar", "Haque", "Talukder");
  private static final List<String> SHOP_KIND = List.of(
      "Telecom", "Store", "Enterprise", "Mobile Point", "General Store");
  private static final List<String> MERCHANTS = List.of(
      "Karim Traders", "Lalbagh Pharmacy", "Gulshan Fashion House", "Mirpur Super Shop", "Banani Cafe",
      "Uttara Electronics", "Motijheel Stationery", "Sylhet Tea House", "Rajshahi Mango Store", "Khulna Sweets",
      "Bogura Doi Ghar", "Old Dhaka Biryani House", "Farmgate Mobile Centre", "Mohammadpur Hardware",
      "Bashundhara Books", "Narayanganj Textiles", "Comilla Rasmalai Bhandar", "Barishal Rice Agency",
      "Gazipur Furniture Mart", "Tejgaon Auto Parts");
  private static final List<String> ORGS = List.of("Meghna Garments Ltd", "Padma Foods Ltd", "Karnaphuli Shipping Ltd");
  private static final List<String> DISTRIBUTORS = List.of(
      "Dhaka Distribution House", "Chattogram Trade Link", "Sylhet Mobile Distribution", "Rajshahi Agro Distributors",
      "Khulna Distribution Point", "Barishal Supply Chain", "Rangpur Trade House", "Mymensingh Distributors",
      "Comilla Distribution Centre", "Gazipur Wholesale Link");

  private HolderNames() {}

  /** The holder name of the {@code n}-th (1-based) generated wallet of {@code type}; null when none. */
  public static String of(String type, int n) {
    if (n < 1 || type == null) {
      return null;
    }
    int i = n - 1;
    return switch (type) {
      case "CUSTOMER" -> i % 2 == 0 ? pick(MALE_FIRST, MALE_LAST, i / 2) : pick(FEMALE_FIRST, FEMALE_LAST, i / 2);
      case "AGENT" -> pick(SHOP_OWNER, SHOP_KIND, i);
      case "MERCHANT" -> i < MERCHANTS.size() ? MERCHANTS.get(i) : MERCHANTS.get(i % MERCHANTS.size()) + " " + (i / MERCHANTS.size() + 1);
      case "ORG", "ORGANISATION" -> i < ORGS.size() ? ORGS.get(i) : "Org " + n + " Ltd";
      case "DISTRIBUTOR" -> i < DISTRIBUTORS.size() ? DISTRIBUTORS.get(i) : "Distributor " + n;
      default -> null;
    };
  }

  /** first[k mod |first|] + last[(k mod |first| + k / |first|) mod |last|]: distinct for k below |first| * |last|. */
  private static String pick(List<String> first, List<String> last, int k) {
    int a = k % first.size();
    int b = k / first.size();
    return first.get(a) + " " + last.get((a + b) % last.size());
  }
}
