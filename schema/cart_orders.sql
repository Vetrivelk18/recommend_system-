-- Cart and order flow. Run this on Supabase BEFORE deploying the matching code.
--
-- No JPA entity maps these tables - the repositories use native SQL, the same way
-- SearchRepository handles last_search. That is deliberate: ddl-auto=validate only
-- checks mapped entities, so the application still starts if this file has not been
-- applied yet. The failure then shows up as a failing cart request rather than a
-- refusal to boot.
--
-- Naming: plain "orders" / "order_items" is safe - the public schema has no table of
-- either name (checked 2026-09-30). "order" singular would have been a reserved word.

create table if not exists cart_items (
    user_id    integer     not null references users(user_id) on delete cascade,
    product_id integer     not null references products(product_id),
    quantity   integer     not null check (quantity > 0 and quantity <= 99),
    added_at   timestamptz not null default now(),
    primary key (user_id, product_id)
);

-- One row per user per product, so adding the same product twice increments rather than
-- duplicating. There is no cart header table: a user has exactly one cart, so the user
-- IS the cart and there is no cart_id to keep in sync.

create table if not exists orders (
    order_id       bigserial   primary key,
    user_id        integer     not null references users(user_id),
    status         text        not null default 'PLACED',
    total_quantity integer     not null check (total_quantity > 0),
    placed_at      timestamptz not null default now()
);

create index if not exists orders_user_placed_idx on orders (user_id, placed_at desc);

create table if not exists order_items (
    order_id     bigint  not null references orders(order_id) on delete cascade,
    product_id   integer not null references products(product_id),
    product_name text    not null,
    quantity     integer not null check (quantity > 0),
    primary key (order_id, product_id)
);

-- product_name is snapshotted at checkout on purpose. The cart joins products live, so a
-- renamed product shows its current name there; an order is a record of what happened and
-- must not change when the catalogue does.

-- Note: unlike last_search, both tables carry a real foreign key to users(user_id), so an
-- arbitrary user id cannot be written through the API.
