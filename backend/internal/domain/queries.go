package domain

type RecordQuery struct {
	Table           string
	After           string
	Limit           int
	Search          string
	FromMs          int64
	ToMs            int64
	DateField       string
	BillKind        string
	BillCategory    *string
	ExcludeRefunded bool
}

type BillMonthStatistics struct {
	Month               int   `json:"month"`
	FromMs              int64 `json:"fromMs"`
	ToMs                int64 `json:"toMs"`
	ExpenseCents        int64 `json:"expenseCents"`
	IncomeCents         int64 `json:"incomeCents"`
	StoredValueUseCents int64 `json:"storedValueUseCents"`
}
type BillCategoryStatistics struct {
	Category     string `json:"category"`
	ExpenseCents int64  `json:"expenseCents"`
	Count        int64  `json:"count"`
}
type BillStatistics struct {
	Year           int                      `json:"year"`
	Timezone       string                   `json:"timezone"`
	Months         []BillMonthStatistics    `json:"months"`
	PreviousMonths []BillMonthStatistics    `json:"previousMonths"`
	Categories     []BillCategoryStatistics `json:"categories"`
}
type RecordPage struct {
	Rows       []Row  `json:"rows"`
	NextCursor string `json:"nextCursor"`
	More       bool   `json:"more"`
}
type Statistics struct {
	RecordCounts            map[string]int64 `json:"recordCounts"`
	BillExpenseCents        int64            `json:"billExpenseCents"`
	BillIncomeCents         int64            `json:"billIncomeCents"`
	BillStoredValueUseCents int64            `json:"billStoredValueUseCents"`
}
