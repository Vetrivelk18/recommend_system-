export interface Option {
  id: number;
  name: string;
}

export interface OrganicOption {
  value: string;
  label: string;
}

export interface OnboardingOptions {
  departments: Option[];
  aisles: Option[];
  days: string[];
  times: string[];
  organic: OrganicOption[];
}

/** Keys are upper-case to match users.preferences jsonb exactly. */
export interface Preferences {
  DEPARTMENT: string;
  AISLE: string;
  DOW: string;
  HOUR: string;
  ORGANIC: string;
}

export interface SignupRequest {
  name: string;
  mobile: string;
  password: string;
  preferences?: Preferences;
}

export interface AuthResponse {
  token: string;
  userId: number;
  name: string;
  preferences?: Preferences;
}

export interface DashboardResponse {
  userId: number;
  name: string;
  forYouTitle: string | null;
  forYou: string[];
  runningLow: string[];
  popular: string[];
}

export interface SearchResult {
  productId: number;
  product: string;
}

export interface CartLine {
  productId: number;
  product: string;
  quantity: number;
  inStock: boolean;
}

/** lineCount is distinct products, totalQuantity is units. */
export interface CartView {
  userId: number;
  lines: CartLine[];
  lineCount: number;
  totalQuantity: number;
}

export interface OrderSummary {
  orderId: number;
  placedAt: string;
  status: string;
  totalQuantity: number;
  lineCount: number;
}

/** product is the name snapshotted at checkout, not the catalogue's current one. */
export interface OrderLine {
  productId: number;
  product: string;
  quantity: number;
}

export interface OrderDetail {
  order: OrderSummary;
  lines: OrderLine[];
}
