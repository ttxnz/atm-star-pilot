-- Own isolated End network only. Native wired inventory transfers; no item deletion.
local names = peripheral.getNames()
local function unique(prefix)
    local found
    for _, name in ipairs(names) do
        if name:sub(1, #prefix) == prefix and peripheral.hasType(name, "inventory") then
            assert(not found, "Ambiguous own inventory: " .. prefix)
            found = name
        end
    end
    assert(found, "Connect and activate modem: " .. prefix)
    return found, peripheral.wrap(found)
end
local inputName, input = unique("sophisticatedstorage:barrel_")
local tableName, salvage = unique("apotheosis:salvaging_table_")
local exportName, export = unique("minecraft:barrel_")
assert(inputName ~= exportName and tableName ~= exportName, "Distinct inventories required")
local counts = { materials = 0, equipment = 0, reclaimed = 0 }
local details, retry = {}, {}
local function backpack(item)
    return item and item.name:lower():find("backpack", 1, true) ~= nil
end
local function material(item)
    return item and type(item.maxCount) == "number" and item.maxCount > 1 and not backpack(item)
end
local function route(source, destination, isTable)
    local slots = source.list()
    for slot, brief in pairs(slots) do
        local key = brief.name .. ":" .. (brief.nbt or "")
        local item = details[key]
        if not item then
            item = source.getItemDetail(slot)
            if item then details[key] = item end
        end
        -- No NBT/component restriction. Every stackable material uses the same route.
        if material(item) then
            local moved = source.pushItems(exportName, slot, brief.count)
            local key = isTable and "reclaimed" or "materials"
            counts[key] = counts[key] + moved
        elseif not isTable and item and not backpack(item) and item.maxCount == 1
            and (not retry[slot] or retry[slot].key ~= key or retry[slot].at <= os.clock()) then
            -- Native salvaging-table insertion rules decide whether the item is recyclable.
            -- Rejected equipment stays in the independent input barrel.
            local moved = source.pushItems(destination, slot, brief.count)
            counts.equipment = counts.equipment + moved
            retry[slot] = { key = key, at = os.clock() + (moved > 0 and 0.1 or 5) }
        end
    end
end
term.clear()
term.setCursorPos(1, 1)
print("ATM End loot router")
print("Stackable materials -> AE")
print("Equipment -> native salvage")
print("Backpacks/rejected gear retained")
while true do
    route(salvage, exportName, true)
    route(input, tableName, false)
    term.setCursorPos(1, 6)
    term.clearLine()
    write("AE materials: " .. counts.materials)
    term.setCursorPos(1, 7)
    term.clearLine()
    write("Salvaged outputs: " .. counts.reclaimed)
    term.setCursorPos(1, 8)
    term.clearLine()
    write("Gear accepted: " .. counts.equipment)
    term.setCursorPos(1, 10)
    term.clearLine()
    write("Running " .. math.floor(os.clock()) .. "s")
    sleep(0.1)
end
